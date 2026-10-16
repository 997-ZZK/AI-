package com.aiflow.engine.provider;

import java.util.Base64;
import java.util.Locale;

/**
 * 随消息发给多模态模型的一张图片。
 *
 * <p>只有两种合法形态：
 * <ul>
 *   <li>{@code https://...} 公网直链 —— 由厂商侧去抓取（对象存储直链；M2 待补的 MinIO 落地后走这条）</li>
 *   <li>{@code data:image/png;base64,...} 内联二进制 —— 本地服务与内网端点只能这么传</li>
 * </ul>
 *
 * <p>刻意不支持「本地文件路径」：那既是 SSRF 面（让上游去读任意地址），也是信息泄露面（把服务器目录结构发给厂商）。
 * 本地图片必须先落 AttachmentStore，再由它编码成 data URI。
 *
 * <p>{@link #toString()} 只输出类型与长度：record 的默认实现会把整段 base64 打进日志、异常栈与快照。
 */
public record ChatImage(String url) {

    private static final String BASE64_MARKER = ";base64,";
    private static final int PREVIEW_LIMIT = 48;

    public ChatImage {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("图片引用为空");
        }
        url = url.strip();
        if (!isSupported(url)) {
            throw new IllegalArgumentException("不支持的图片引用（只接受 http(s) 直链或 data:image base64）: " + preview(url));
        }
    }

    public static ChatImage ofUrl(String url) {
        return new ChatImage(url);
    }

    public static ChatImage ofBytes(String mimeType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        String mime = mimeType == null || mimeType.isBlank()
                ? "image/png" : mimeType.strip().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/") || mime.contains(";")) {
            throw new IllegalArgumentException("不是图片 MIME 类型: " + mime);
        }
        return new ChatImage("data:" + mime + BASE64_MARKER + Base64.getEncoder().encodeToString(bytes));
    }

    public boolean inline() {
        return url.regionMatches(true, 0, "data:", 0, 5);
    }

    /** 内联图片的 MIME；直链无法从 url 可靠推断，统一返回 image/*。 */
    public String mediaType() {
        if (!inline()) {
            return "image/*";
        }
        int semicolon = url.indexOf(';');
        return semicolon < 0 ? "image/*" : url.substring(5, semicolon);
    }

    /** 可安全写进日志的摘要。 */
    public String summary() {
        return inline() ? mediaType() + " inline（" + url.length() + " 字符）" : url;
    }

    @Override
    public String toString() {
        return "ChatImage[" + summary() + "]";
    }

    private static boolean isSupported(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return true;
        }
        return lower.startsWith("data:image/") && lower.contains(BASE64_MARKER);
    }

    private static String preview(String url) {
        return url.length() <= PREVIEW_LIMIT ? url : url.substring(0, PREVIEW_LIMIT) + "…";
    }
}