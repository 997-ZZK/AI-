package com.aiflow.engine.integration.audio;

import com.aiflow.engine.audio.TtsRequest;
import com.aiflow.engine.audio.TtsResponse;

/** 厂商无关的本地 TTS sidecar 编码/解码 SPI。 */
public interface LocalTtsProtocol extends AudioHttpProtocol<TtsRequest, TtsResponse> {
}
