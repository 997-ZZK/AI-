package com.aiflow.engine.integration.audio;

/** 厂商编码/解码边界；没有内置假定的语音路径、鉴权或私有字段。 */
public interface AudioHttpProtocol<Q, S> {

    enum Kind { SYNCHRONOUS, ASYNCHRONOUS, WEBSOCKET }

    default Kind kind() {
        return Kind.SYNCHRONOUS;
    }

    AudioHttpRequest encode(Q request);

    /** 仅在 HTTP 200 时调用；不得把厂商错误原文放入异常或返回元数据。 */
    S decode(AudioHttpResponse response, Q request);
}
