package com.aiflow.engine.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioLimitsTest {

    @Test
    void rejectsUnsupportedPcmParameters() {
        assertThatThrownBy(() -> limits(16_000, 1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(192_001, 1, 16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(16_000, 3, 16)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsSupportedPcmBitDepths() {
        for (int bits : new int[]{8, 16, 24, 32}) {
            assertThat(limits(48_000, 2, bits).bitsPerSample()).isEqualTo(bits);
        }
    }

    @Test
    void rejectsDurationThatCannotBeRepresentedAsPositiveMilliseconds() {
        assertThatThrownBy(() -> new AudioLimits(100, Duration.ofNanos(1), 16_000, 1, 16))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioLimits(100, Duration.ofSeconds(Long.MAX_VALUE), 16_000, 1, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AudioLimits limits(int sampleRate, int channels, int bits) {
        return new AudioLimits(5 * 1024 * 1024L, Duration.ofSeconds(30), sampleRate, channels, bits);
    }
}
