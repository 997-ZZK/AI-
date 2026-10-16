package com.aiflow.engine.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** 合成 WAV 仅在测试内存中生成，不把音频原件放进源码或报告。 */
final class AudioApiTestSupport {

    static final String TRANSCRIPTIONS = "/api/v1/audio/transcriptions";
    static final String SPEECH = "/api/v1/audio/speech";

    private AudioApiTestSupport() {
    }

    static byte[] wav(int sampleRate, int channels, int frames) {
        int dataBytes = frames * channels * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataBytes)
                .put(new byte[]{'W', 'A', 'V', 'E'})
                .put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1)
                .putShort((short) channels).putInt(sampleRate).putInt(sampleRate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataBytes);
        return buffer.array();
    }

    static byte[] shortWav() {
        return wav(16_000, 1, 1_600);
    }

    static byte[] wavWithTotalBytes(int totalBytes) {
        byte[] source = shortWav();
        int junkBytes = totalBytes - source.length - 8;
        if (junkBytes < 0 || (junkBytes & 1) != 0) {
            throw new IllegalArgumentException("Test WAV requires an even JUNK payload");
        }
        byte[] result = Arrays.copyOf(source, totalBytes);
        ByteBuffer buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4, totalBytes - 8);
        buffer.position(source.length);
        buffer.put(new byte[]{'J', 'U', 'N', 'K'}).putInt(junkBytes);
        return result;
    }
}
