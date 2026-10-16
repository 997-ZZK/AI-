package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.AsrRequest;
import com.aiflow.engine.audio.AsrResponse;

/** 厂商无关的本地 ASR sidecar 编码/解码 SPI。 */
public interface LocalAsrProtocol extends AudioHttpProtocol<AsrRequest, AsrResponse> {
}
