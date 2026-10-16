package com.aiflow.engine.integration.audio;

import java.util.Arrays;

/** 只保留协议解码需要的字段，不透传厂商响应头或错误报文。 */
public record AudioHttpResponse(int status, String contentType, byte[] body) {

    public AudioHttpResponse {
        if (status < 100 || status > 599 || body == null) {
            throw new IllegalArgumentException("无效音频 HTTP 响应");
        }
        contentType = contentType == null ? "" : contentType;
        body = Arrays.copyOf(body, body.length);
    }

    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    @Override
    public String toString() {
        return "AudioHttpResponse[status=" + status + ", bodyBytes=" + body.length + "]";
    }
}
