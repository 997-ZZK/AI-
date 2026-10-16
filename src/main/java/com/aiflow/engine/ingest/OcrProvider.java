package com.aiflow.engine.ingest;

/**
 * OCR 能力：把一张图片变成文本。
 *
 * <p>接口放在 {@code ingest}（纯解析层），实现放在 {@code integration}（对外调用）：解析流程不该知道
 * 「文字是哪个厂商的模型认出来的」，只该知道「这里需要一次识别」。这样扫描件入库既可以用云端多模态档，
 * 也可以换成专用 OCR 引擎或本地模型，而流水线不动。
 *
 * <p>与检索链路的 embedding 一样，OCR 也是**有成本的外部调用**：调用方负责限制页数，接口本身不兜底。
 */
public interface OcrProvider {

    /** 实现标识（写进日志与文档元数据，便于回溯「这段文字是谁认出来的」）。 */
    String id();

    /** 现在能不能用（没有具备 vision 能力的档位时为 false，用于给出可操作的报错）。 */
    boolean available();

    /**
     * 识别一张图片里的文字。
     *
     * @param imageBytes 图片字节（png / jpeg / webp / gif）
     * @param mimeType   图片 MIME 类型
     * @return 识别出的纯文本（可能为空字符串，表示图上没有文字）
     * @throws OcrException 识别不可用或调用失败
     */
    String recognize(byte[] imageBytes, String mimeType);
}
