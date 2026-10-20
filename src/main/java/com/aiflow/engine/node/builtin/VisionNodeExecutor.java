package com.aiflow.engine.node.builtin;

import com.aiflow.engine.attachment.AttachmentStore;
import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.config.AttachmentProperties;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.SessionMemoryPort;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.TemplateRenderer;
import com.aiflow.engine.provider.AllProvidersFailedException;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.CapabilityUnavailableException;
import com.aiflow.engine.provider.ChatImage;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.safety.ContentSafety;
import com.aiflow.engine.safety.StreamingSafetyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 多模态节点：把「消息里的图片引用」解析成图片，连同提示词交给同时具备 chat + vision 的档位。
 *
 * <p>图片引用只接受两种形态：attachmentId（先 POST /api/v1/attachments 上传）或 http(s) 直链。
 * 本地文件路径既不接受（SSRF 与目录泄露面）也不解析。
 *
 * <p>能力门槛是 chat + vision 两个都要：只声明 chat 的档位会被排除。没有这样的档位时，
 * 先给出这条可操作的结论，再谈图片本身上下 —— 报错顺序反了会把排查带偏。
 */
@Component
public class VisionNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(VisionNodeExecutor.class);
    private static final Pattern REF_SEPARATOR = Pattern.compile("[,\\s]+");

    private final ProviderRouter providerRouter;
    private final AttachmentStore attachmentStore;
    private final AttachmentProperties attachmentProperties;
    private final ContentSafety contentSafety;
    private final SafetyProperties safetyProperties;
    private final TokenBudgetService tokenBudgetService;
    private final BudgetProperties budgetProperties;
    private final StreamingSafetyGuard streamingSafety;
    private final SessionContextProvider sessionContext;

    /** 测试与最小装配用：不带会话记忆（等价于窗口注入关闭）。 */
    public VisionNodeExecutor(ProviderRouter providerRouter, AttachmentStore attachmentStore,
                              AttachmentProperties attachmentProperties, ContentSafety contentSafety,
                              SafetyProperties safetyProperties, TokenBudgetService tokenBudgetService,
                              BudgetProperties budgetProperties, StreamingSafetyGuard streamingSafety) {
        this(providerRouter, attachmentStore, attachmentProperties, contentSafety, safetyProperties,
                tokenBudgetService, budgetProperties, streamingSafety,
                new SessionContextProvider(SessionMemoryPort.disabled(), MemoryProperties.defaults()));
    }

    @Autowired
    public VisionNodeExecutor(ProviderRouter providerRouter, AttachmentStore attachmentStore,
                              AttachmentProperties attachmentProperties, ContentSafety contentSafety,
                              SafetyProperties safetyProperties, TokenBudgetService tokenBudgetService,
                              BudgetProperties budgetProperties, StreamingSafetyGuard streamingSafety,
                              SessionContextProvider sessionContext) {
        this.providerRouter = providerRouter;
        this.attachmentStore = attachmentStore;
        this.attachmentProperties = attachmentProperties;
        this.contentSafety = contentSafety;
        this.safetyProperties = safetyProperties;
        this.tokenBudgetService = tokenBudgetService;
        this.budgetProperties = budgetProperties;
        this.streamingSafety = streamingSafety;
        this.sessionContext = sessionContext;
    }

    @Override
    public String type() {
        return "llm.vision";
    }

    @Override
    public Set<String> requiredCapabilities() {
        return Set.of(Capabilities.CHAT, Capabilities.VISION);
    }

    @Override
    public NodeResult execute(NodeContext context) {
        // 额度检查放在最前：读盘之前就能把「今天已经没有额度了」这件事说完，不必先把图片读进来。
        TokenBudgetService.Snapshot budget = tokenBudgetService.snapshot(context.userId());
        if (budget.exhausted()) {
            return BudgetBlocks.exhausted(context, new LinkedHashMap<>(), budget, budgetProperties.exhaustedReply());
        }
        String prompt = TemplateRenderer.render(context.paramString("prompt", "${input}"), context.variables());
        String refs = TemplateRenderer.render(context.paramString("images", "${attachments}"), context.variables());
        List<String> tokens = splitRefs(refs);
        if (tokens.isEmpty()) {
            return NodeResult.failed("没有可用的图片引用：先 POST /api/v1/attachments 上传图片，"
                    + "再把 attachmentId 放进消息的 attachmentIds（节点参数 images 默认取运行变量 attachments）");
        }
        int maxImages = attachmentProperties.maxPerRequest();
        if (tokens.size() > maxImages) {
            return NodeResult.failed("一次最多处理 " + maxImages + " 张图片，本次收到 " + tokens.size() + " 张");
        }
        try {
            // 先问「有没有同时具备 chat + vision 的档位」，再去读磁盘：
            // 没有档位时先把几十 MB 图片读进来、再报「没有 vision」，白花时间且把排查方向带偏。
            providerRouter.route(requiredCapabilities());
        } catch (CapabilityUnavailableException e) {
            return NodeResult.failed(visionUnavailableHint());
        }
        List<ChatImage> images = new ArrayList<>(tokens.size());
        try {
            for (String token : tokens) {
                images.add(resolve(token));
            }
        } catch (IllegalArgumentException | BizException e) {
            return NodeResult.failed(e.getMessage());
        }
        String model = context.paramString("model", null);
        try {
            // 与 llm.chat 同一层增量审核：图片问答同样是流式推送，不能只审完整回复。
            StreamingSafetyGuard.Session stream = streamingSafety.open(context::emitDelta);
            // 会话记忆与 llm.chat 共用同一份装配口径；历史里的图片早已降级为文本，这里只带本轮图片
            SessionContextProvider.Assembly assembly = sessionContext.assembleDetailed(context.sessionId(), context.userId(),
                    ChatMessage.user(prompt, images), context.paramString("history", null),
                    context.paramString("includeSummary", null), context.paramString("longMemory", null),
                    context.paramString("profilePreference", null), model);
            ProviderRouter.ChatOutcome outcome = providerRouter.chat(requiredCapabilities(),
                    new ChatRequest(model, assembly.messages()), stream::accept,
                    budget.soft() || assembly.preferences().preferCheapTiers());
            stream.finish();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("reply", outcome.response().content());
            output.put("imageCount", images.size());
            // 只留可诊断引用，不留 base64、userinfo 或 query token：否则 node_runs 快照会变成图片仓库/凭证仓库。
            output.put("imageRefs", diagnosticRefs(tokens));
            output.putAll(DegradationTrace.describe(outcome));
            // 图片也按 token 计费（多模态输入的 token 通常比文本贵），同样在返回后、审核前扣减
            long usedTokens = outcome.response().promptTokens() + outcome.response().completionTokens();
            BudgetBlocks.record(context, output, tokenBudgetService.charge(context.userId(), usedTokens), usedTokens);
            ContentSafety.Verdict verdict = stream.blocked()
                    ? stream.verdict()
                    : contentSafety.review(outcome.response().content(), ContentSafety.Direction.OUTPUT);
            if (!verdict.allowed()) {
                return SafetyBlocks.degraded(context, output, verdict, safetyProperties.rejectedReply());
            }
            context.setVariable("reply", outcome.response().content());
            context.setVariable("imageCount", images.size());
            return NodeResult.success(output);
        } catch (CapabilityUnavailableException e) {
            return NodeResult.failed(visionUnavailableHint());
        } catch (AllProvidersFailedException e) {
            return NodeResult.failed(e.getMessage());
        }
    }

    private static String visionUnavailableHint() {
        return "没有同时具备 chat 与 vision 能力的档位："
                + "给云端端点声明 capabilities: [chat, vision] 并配置 VL 模型，或启用本地多模态模型";
    }

    private ChatImage resolve(String token) {
        if (token.regionMatches(true, 0, "http://", 0, 7)
                || token.regionMatches(true, 0, "https://", 0, 8)) {
            return ChatImage.ofUrl(token);
        }
        UUID id;
        try {
            id = UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无法识别的图片引用（只接受 attachmentId 或 http(s) 直链）");
        }
        AttachmentStore.Loaded loaded = attachmentStore.load(id);
        log.info("vision resolved attachment id={} mime={} bytes={}", id, loaded.mimeType(), loaded.bytes().length);
        return ChatImage.ofBytes(loaded.mimeType(), loaded.bytes());
    }

    private static List<String> diagnosticRefs(List<String> tokens) {
        return tokens.stream().map(token -> {
            if (token.regionMatches(true, 0, "http://", 0, 7)
                    || token.regionMatches(true, 0, "https://", 0, 8)) {
                return ChatImage.ofUrl(token).summary();
            }
            return token;
        }).toList();
    }

    private static List<String> splitRefs(String refs) {
        if (refs == null || refs.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : REF_SEPARATOR.split(refs.strip())) {
            if (!token.isBlank()) {
                tokens.add(token.strip());
            }
        }
        return tokens;
    }
}
