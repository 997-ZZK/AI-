package com.aiflow.engine.integration;

import com.aiflow.engine.ingest.OcrException;
import com.aiflow.engine.ingest.OcrProvider;
import com.aiflow.engine.provider.CapabilityUnavailableException;
import com.aiflow.engine.provider.ChatImage;
import com.aiflow.engine.provider.ChatMessage;
import com.aiflow.engine.provider.ChatRequest;
import com.aiflow.engine.provider.ProviderRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 用多模态模型当 OCR 引擎。
 *
 * <p>为什么复用对话档而不是接一个专用 OCR SDK：多模态档已经具备「图片 -> 文字」这一能力，再接一家
 * 只是多一份密钥、多一套配额与降级逻辑；而且专用 OCR 不保证本机可跑，这条链路一旦依赖它，
 * 「断网也能入库」就没了（本项目的分级策略里，本地档是兜底）。
 *
 * <p>提示词按「转写」而不是「描述」写：模型的默认倾向是总结与解释，那对知识库是有毒的 ——
 * 入库的必须是原文，不是二手转述。看不清的字符要求占位而不是猜，猜出来的字进了向量库，谁也查不出来。
 *
 * <p>这里**不做 token 计费**：入库是运营动作，不是用户会话动作，没有 userId 可记。成本控制靠
 * {@code aiflow.ingest.ocr.max-pages} 这个页数闸，比按 token 记更直观（一次识别 = 一页）。
 */
@Component
public class VisionOcrProvider implements OcrProvider {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrProvider.class);
    private static final Set<String> REQUIRED = Set.of("vision");
    private static final String PROMPT = """
            你是 OCR 引擎。把图片里的文字逐字转写成纯文本，保留原有的换行与列表结构。
            规则：
            1. 不要翻译、不要总结、不要解释、不要补充图里没有的内容；
            2. 表格按「单元格内容用 | 分隔、每行一条」输出；
            3. 看不清的字符用 □ 占位，不要猜测；
            4. 图片里没有文字时，只输出：NO_TEXT。
            只输出正文本身，不要任何前后缀说明。""";

    private final ProviderRouter providerRouter;

    public VisionOcrProvider(ProviderRouter providerRouter) {
        this.providerRouter = providerRouter;
    }

    @Override
    public String id() {
        return "vision-ocr";
    }

    @Override
    public boolean available() {
        try {
            providerRouter.route(REQUIRED);
            return true;
        } catch (CapabilityUnavailableException e) {
            return false;
        }
    }

    @Override
    public String recognize(byte[] imageBytes, String mimeType) {
        try {
            ProviderRouter.ChatOutcome outcome = providerRouter.chat(REQUIRED,
                    new ChatRequest(null, List.of(ChatMessage.user(PROMPT, List.of(ChatImage.ofBytes(mimeType, imageBytes))))),
                    null);
            String text = outcome.response().content() == null ? "" : outcome.response().content().strip();
            log.info("OCR 完成 provider={} model={} chars={} imageBytes={}", outcome.providerId(),
                    outcome.response().model(), text.length(), imageBytes.length);
            if ("NO_TEXT".equals(text)) {
                return "";
            }
            return text;
        } catch (CapabilityUnavailableException e) {
            throw OcrException.unavailable();
        } catch (RuntimeException e) {
            // 厂商正文、URL、凭证片段和原始异常链都属于内部诊断，不能进入文档错误/API。
            throw OcrException.callFailed();
        }
    }
}
