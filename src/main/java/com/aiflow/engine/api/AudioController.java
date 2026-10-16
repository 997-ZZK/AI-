package com.aiflow.engine.api;

import com.aiflow.engine.api.dto.AudioSpeechRequest;
import com.aiflow.engine.api.dto.AudioTranscriptionResponse;
import com.aiflow.engine.api.service.AudioService;
import com.aiflow.engine.audio.TtsResponse;
import com.aiflow.engine.common.api.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 一次性同步音频接口；不把音频写入附件、会话或运行记录。 */
@RestController
@RequestMapping("/api/v1/audio")
public class AudioController {

    private final AudioService audioService;

    public AudioController(AudioService audioService) {
        this.audioService = audioService;
    }

    @PostMapping(value = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AudioTranscriptionResponse>> transcribe(
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(audioService.transcribe(file)));
    }

    @PostMapping(value = "/speech", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> speech(@RequestBody AudioSpeechRequest request) {
        TtsResponse response = audioService.synthesize(request);
        byte[] bytes = response.audioBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .contentLength(bytes.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"speech.wav\"")
                .header("X-Audio-Provider", response.provider())
                .header("X-Audio-Model", response.model())
                .header("X-Audio-Duration-Ms", Long.toString(response.durationMs()))
                .body(bytes);
    }
}
