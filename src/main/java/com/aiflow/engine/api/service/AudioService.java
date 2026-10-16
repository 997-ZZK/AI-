package com.aiflow.engine.api.service;

import com.aiflow.engine.api.dto.AudioLimitsResponse;
import com.aiflow.engine.api.dto.AudioSpeechRequest;
import com.aiflow.engine.api.dto.AudioTranscriptionResponse;
import com.aiflow.engine.audio.AsrProvider;
import com.aiflow.engine.audio.AsrResponse;
import com.aiflow.engine.audio.AudioInput;
import com.aiflow.engine.audio.AudioOperations;
import com.aiflow.engine.audio.AudioLimits;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.audio.TtsProvider;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.config.AudioProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** HTTP 音频应用服务：限制输入内存读取，校验输出，保持一次请求内的临时字节。 */
@Service
public class AudioService {

    private final AudioProperties properties;
    private final AudioOperations operations;

    /** Compatibility constructor retained for the D1 pure unit tests. */
    public AudioService(AudioProperties properties, ObjectProvider<AsrProvider> asrProviders,
                        ObjectProvider<TtsProvider> ttsProviders) {
        this.properties = properties;
        this.operations = new AudioOperations(policy(properties), asrProviders::getIfAvailable,
                ttsProviders::getIfAvailable);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AudioService(AudioProperties properties, AudioOperations operations) {
        this.properties = properties;
        this.operations = operations;
    }

    public AudioTranscriptionResponse transcribe(MultipartFile file) {
        AudioLimits limits = properties.limits().toAudioLimits();
        // Keep the capability gate ahead of all request stream access.  A disabled or
        // unavailable channel must reject without opening/reading the multipart body.
        AsrResponse response = operations.transcribe(() -> {
            if (file == null || file.getSize() <= 0) {
                throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
            }
            if (file.getSize() > limits.maxBytes()) {
                throw new AudioValidationException(AudioValidationException.Reason.SIZE_LIMIT);
            }
            String mimeType = AudioOperations.requireWavMediaType(file.getContentType());
            byte[] bytes = readLimited(file, limits.maxBytes());
            return new AudioInput(bytes, mimeType);
        });
        return new AudioTranscriptionResponse(response.text(), response.provider(), response.model(), response.durationMs(),
                AudioLimitsResponse.from(limits));
    }

    public TtsResponse synthesize(AudioSpeechRequest request) {
        if (request == null) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
        return operations.synthesize(request.text(), request.voice(), request.format());
    }

    private static byte[] readLimited(MultipartFile file, long maxBytes) {
        try (InputStream stream = file.getInputStream(); ByteArrayOutputStream collected = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long count = 0;
            while (true) {
                // At the exact limit, only consume one more byte to detect a dishonest size declaration.
                long remaining = maxBytes - count;
                int allowed = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
                int read = stream.read(buffer, 0, allowed);
                if (read < 0) {
                    return collected.toByteArray();
                }
                if (read == 0) {
                    // InputStream implementations must make progress even when bulk read returns zero.
                    int single = stream.read();
                    if (single < 0) {
                        return collected.toByteArray();
                    }
                    buffer[0] = (byte) single;
                    read = 1;
                }
                count += read;
                if (count > maxBytes || count > Integer.MAX_VALUE - 8L) {
                    throw new AudioValidationException(AudioValidationException.Reason.SIZE_LIMIT);
                }
                collected.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        } catch (AudioValidationException knownFailure) {
            // Closing a Servlet stream can add a suppressed exception containing upload details.
            throw new AudioValidationException(knownFailure.reason());
        } catch (RuntimeException ignored) {
            throw new AudioValidationException(AudioValidationException.Reason.INVALID_REQUEST);
        }
    }

    private static AudioOperations.Policy policy(AudioProperties properties) {
        return new AudioOperations.Policy(properties.enabled(), properties.mockMode(),
                new AudioOperations.Channel(properties.asr().enabled(), properties.asr().provider(), properties.asr().model()),
                new AudioOperations.Channel(properties.tts().enabled(), properties.tts().provider(), properties.tts().model()),
                properties.limits().toAudioLimits());
    }
}
