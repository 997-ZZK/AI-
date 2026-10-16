package com.aiflow.engine.node.builtin;

import com.aiflow.engine.budget.TokenBudgetService;
import com.aiflow.engine.memory.MemoryProperties;
import com.aiflow.engine.memory.SessionContextProvider;
import com.aiflow.engine.memory.SessionMemoryPort;
import com.aiflow.engine.config.BudgetProperties;
import com.aiflow.engine.config.SafetyProperties;
import com.aiflow.engine.node.NodeContext;
import com.aiflow.engine.node.NodeExecutor;
import com.aiflow.engine.node.NodeResult;
import com.aiflow.engine.node.TemplateRenderer;
import com.aiflow.engine.provider.AllProvidersFailedException;
import com.aiflow.engine.provider.Capabilities;
import com.aiflow.engine.provider.CapabilityUnavailableException;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ProviderRouter;
import com.aiflow.engine.safety.ContentSafety;
import com.aiflow.engine.safety.StreamingSafetyGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class LlmNodeExecutor implements NodeExecutor {

    private final ProviderRouter providerRouter;
    private final ContentSafety contentSafety;
    private final SafetyProperties safetyProperties;
    private final TokenBudgetService tokenBudgetService;
    private final BudgetProperties budgetProperties;
    private final StreamingSafetyGuard streamingSafety;
    private final SessionContextProvider sessionContext;

    /** 测试与最小装配用：不带会话记忆（等价于窗口注入关闭）。 */
    public LlmNodeExecutor(ProviderRouter providerRouter, ContentSafety contentSafety,
                           SafetyProperties safetyProperties, TokenBudgetService tokenBudgetService,
                           BudgetProperties budgetProperties, StreamingSafetyGuard streamingSafety) {
        this(providerRouter, contentSafety, safetyProperties, tokenBudgetService, budgetProperties, streamingSafety,
                new SessionContextProvider(SessionMemoryPort.disabled(), MemoryProperties.defaults()));
    }

    @Autowired
    public LlmNodeExecutor(ProviderRouter providerRouter, ContentSafety contentSafety,
                           SafetyProperties safetyProperties, TokenBudgetService tokenBudgetService,
                           BudgetProperties budgetProperties, StreamingSafetyGuard streamingSafety,
                           SessionContextProvider sessionContext) {
        this.providerRouter = providerRouter;
        this.contentSafety = contentSafety;
        this.safetyProperties = safetyProperties;
        this.tokenBudgetService = tokenBudgetService;
        this.budgetProperties = budgetProperties;
        this.streamingSafety = streamingSafety;
        this.sessionContext = sessionContext;
    }

    @Override
    public String type() {
        return "llm.chat";
    }

    @Override
    public Set<String> requiredCapabilities() {
        return Set.of(Capabilities.CHAT);
    }

    @Override
    public NodeResult execute(NodeContext context) {
        String template = context.paramString("prompt", "${input}");
        String prompt = TemplateRenderer.render(template, context.variables());
        String model = context.paramString("model", null);
        // 额度检查放在最前：不花任何 I/O，却能把「今天已经没有额度了」挡在请求构造与排队之前。
        TokenBudgetService.Snapshot budget = tokenBudgetService.snapshot(context.userId());
        if (budget.exhausted()) {
            return BudgetBlocks.exhausted(context, new LinkedHashMap<>(), budget, budgetProperties.exhaustedReply());
        }
        try {
            // 接近上限（SOFT）时优先便宜档位：降级而不是中断，用户还能用，只是不再用最贵的那一档。
            // 片段先过增量审核再推送：命中会在推送之前被拦下（见 StreamingSafetyGuard 的窗口说明）。
            StreamingSafetyGuard.Session stream = streamingSafety.open(context::emitDelta);
            // 会话记忆与长期记忆：长期记忆 + 摘要 + 最近 N 条历史 + 本轮输入
            // （三块都能被节点参数覆盖：history / includeSummary / longMemory，见 docs/long-memory-design.md §3.4）
            SessionContextProvider.Assembly assembly = sessionContext.assembleDetailed(context.sessionId(), context.userId(),
                    ChatMessage.user(prompt), context.paramString("history", null),
                    context.paramString("includeSummary", null), context.paramString("longMemory", null),
                    context.paramString("profilePreference", null), model);
            ProviderRouter.ChatOutcome outcome = providerRouter.chat(requiredCapabilities(),
                    new ChatRequest(model, assembly.messages()), stream::accept,
                    budget.soft() || assembly.preferences().preferCheapTiers());
            stream.finish();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("reply", outcome.response().content());
            output.putAll(DegradationTrace.describe(outcome));
            // 扣减放在模型返回之后、内容审核之前：内容是否合规，不改变「这些 token 已经花掉了」。
            long tokens = outcome.response().promptTokens() + outcome.response().completionTokens();
            BudgetBlocks.record(context, output, tokenBudgetService.charge(context.userId(), tokens), tokens);
            // 增量审核命中时用它给出的结论（更早、且已阻止片段推送）；否则对完整回复再跑一遍作兜底
            // —— 窗口长度之外的命中、以及长度上限这类「整段属性」的规则，只有这一遍能判。
            ContentSafety.Verdict verdict = stream.blocked()
                    ? stream.verdict()
                    : contentSafety.review(outcome.response().content(), ContentSafety.Direction.OUTPUT);
            if (!verdict.allowed()) {
                return SafetyBlocks.degraded(context, output, verdict, safetyProperties.rejectedReply());
            }
            context.setVariable("reply", outcome.response().content());
            return NodeResult.success(output);
        } catch (CapabilityUnavailableException | AllProvidersFailedException e) {
            return NodeResult.failed(e.getMessage());
        }
    }
}
