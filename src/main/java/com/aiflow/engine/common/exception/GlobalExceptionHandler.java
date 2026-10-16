package com.aiflow.engine.common.exception;

import com.aiflow.engine.audio.AudioProviderException;
import com.aiflow.engine.audio.AudioValidationException;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.provider.CapabilityUnavailableException;
import com.aiflow.engine.retrieval.RetrievalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public ResponseEntity<ApiResponse<Object>> handleBizException(BizException e) {
        return handleBizException(e, null);
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBizException(BizException e, HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        if (isAudioRequest(request)) {
            return audioErrorResponse(errorCode);
        }
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode, e.getMessage(), e.getData()));
    }

    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException e) {
        return handleValidation(e, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException e,
                                                                HttpServletRequest request) {
        if (isAudioRequest(request)) {
            return audioErrorResponse(ErrorCode.BAD_REQUEST);
        }
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, message, null));
    }

    /** provider 不可用（模型、embedding、知识库）：返回 503，让调用方知道是可重试的外部依赖问题。 */
    public ResponseEntity<ApiResponse<Object>> handleCapabilityUnavailable(RuntimeException e) {
        return handleCapabilityUnavailable(e, null);
    }

    @ExceptionHandler({RetrievalException.class, CapabilityUnavailableException.class})
    public ResponseEntity<ApiResponse<Object>> handleCapabilityUnavailable(RuntimeException e,
                                                                          HttpServletRequest request) {
        if (isAudioRequest(request)) {
            log.warn("audio capability unavailable");
            return audioErrorResponse(ErrorCode.CAPABILITY_UNAVAILABLE);
        }
        log.warn("capability unavailable: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.CAPABILITY_UNAVAILABLE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.CAPABILITY_UNAVAILABLE, e.getMessage(), null));
    }

    /** 音频异常只按稳定原因分类；正文或厂商异常消息不参与 HTTP/日志契约。 */
    @ExceptionHandler(AudioValidationException.class)
    public ResponseEntity<ApiResponse<Object>> handleAudioValidation(AudioValidationException e) {
        ErrorCode errorCode = switch (e.reason()) {
            case INVALID_REQUEST, MALFORMED_WAV -> ErrorCode.BAD_REQUEST;
            case UNSUPPORTED_MEDIA, UNSUPPORTED_PCM_PARAMS -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case SIZE_LIMIT, DURATION_LIMIT, TEXT_LIMIT -> ErrorCode.PAYLOAD_TOO_LARGE;
        };
        log.warn("audio request rejected: {} -> {}", e.reason(), errorCode.getCode());
        return audioErrorResponse(errorCode);
    }

    @ExceptionHandler(AudioProviderException.class)
    public ResponseEntity<ApiResponse<Object>> handleAudioProvider(AudioProviderException e) {
        ErrorCode errorCode = switch (e.reason()) {
            case UNAVAILABLE -> ErrorCode.CAPABILITY_UNAVAILABLE;
            case TIMEOUT -> ErrorCode.GATEWAY_TIMEOUT;
            case REJECTED, INVALID_RESPONSE, CALL_FAILED -> ErrorCode.BAD_GATEWAY;
        };
        log.warn("audio provider failure: {} -> {}", e.reason(), errorCode.getCode());
        return audioErrorResponse(errorCode);
    }

    /**
     * 上传超过 spring.servlet.multipart 上限：音频返回 413，既有图片/文档保留 400 契约，
     * 否则兜底 handler 会把它记成 500 + 完整栈，用户看到「服务器内部错误」而不是「文件太大」。
     */
    public ResponseEntity<ApiResponse<Object>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return handleUploadTooLarge(e, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleUploadTooLarge(MaxUploadSizeExceededException e,
                                                                  HttpServletRequest request) {
        log.warn("upload rejected: size limit exceeded");
        if (isAudioRequest(request)) {
            return audioErrorResponse(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST, "上传文件超过大小上限，请压缩后重试", null));
    }

    /**
     * 静态资源缺失（favicon、拼错的静态路径）不是服务端故障：给 404，日志降到 debug。
     * 否则兜底 handler 会把每次控制台刷新都变成一条带完整栈的 ERROR，把真故障淹掉。
     */
    public ResponseEntity<ApiResponse<Object>> handleNoResource(NoResourceFoundException e) {
        return handleNoResource(e, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResource(NoResourceFoundException e,
                                                             HttpServletRequest request) {
        if (isAudioRequest(request)) {
            log.debug("audio resource not found");
            return audioErrorResponse(ErrorCode.NOT_FOUND);
        }
        log.debug("static resource not found: {}", e.getResourcePath());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, "资源不存在: " + e.getResourcePath(), null));
    }

    /**
     * 客户端断流（关标签页、断网、SSE 客户端被杀）不是服务端故障：容器会把它当作异步错误回调回来，
     * 兜底 handler 记成 ERROR + 完整栈，等于把「用户关了个页面」写成告警，真故障会被淹掉。
     * 日志降级为 debug，并且不回响应体 —— 连接已经没了，写什么都到不了对端。
     */
    public void handleClientDisconnect(AsyncRequestNotUsableException e) {
        handleClientDisconnect(e, null);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e, HttpServletRequest request) {
        if (isAudioRequest(request)) {
            log.debug("audio client disconnected, response no longer usable");
            return;
        }
        log.debug("client disconnected, response no longer usable: {}", e.getMessage());
    }

    /** A committed SSE transport can also report a plain, localized servlet IOException. */
    public ResponseEntity<ApiResponse<Object>> handleIoException(IOException e, HttpServletResponse response) {
        return handleIoException(e, response, null);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Object>> handleIoException(IOException e, HttpServletResponse response,
                                                               HttpServletRequest request) {
        String contentType = response.getContentType();
        if (response.isCommitted() && contentType != null
                && MediaType.TEXT_EVENT_STREAM_VALUE.equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            log.debug("SSE transport closed after response commitment");
            return null;
        }
        ResponseEntity<ApiResponse<Object>> error = handleOther(e, request);
        // An uncommitted response may already advertise SSE. A real pre-stream
        // server error must still serialize its ordinary 500 JSON response.
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                .contentType(MediaType.APPLICATION_JSON).body(error.getBody());
    }

    /**
     * Spring MVC 自己抛的**客户端错误**：报文读不出来、方法不对、Content-Type 不对、路径变量类型不对……
     *
     * <p>这一族在语义上都是「调用方写错了」，让它们掉进兜底 handler 有两个后果：调用方把
     * 「请求写错了」当成服务端故障去重试；日志里每次请求都留一条 ERROR 栈，真故障被噪声淹掉。
     * 与静态资源 404、上传超限同口径（见上面的两个 handler）。
     *
     * <p>**为什么不在 @ExceptionHandler 上写 ErrorResponse.class**：注解只接受 Throwable 子类，
     * 而 ErrorResponse 是接口，且实现它的这一族没有共同父类（Servlet 异常一脉继承 ServletException，
     * 其余继承 NestedRuntimeException），穷举类型清单又会漏掉 Spring 后续新增的。
     * 所以改为在兜底 handler 里做一次 instanceof 分流：不穷举、不依赖版本。
     *
     * <p>**不回显报文内容**：Jackson 解析失败的 message 里带着请求体的前几十个字符
     * （形如 {@code [Source: (String)"{"decision":"APPROVE"...}}），那是用户数据，响应与日志都不放它。
     * 所以下面按异常类型重新组织提示语，只取参数名 / 方法名 / Content-Type 这些非载荷信息。
     */
    private ResponseEntity<ApiResponse<Object>> springClientError(Exception e, HttpStatusCode status,
                                                                 HttpServletRequest request) {
        if (status.is5xxServerError()) {
            // Spring 也会抛 5xx 的 ErrorResponse（比如 ResponseStatusException）：那是服务端的事，
            // 不能因为「是 Spring 抛的」就降级成 4xx 放过去（这里不记完整栈：message 可能带请求载荷）
            log.error("Spring 抛了 {}: {}", status.value(), e.getClass().getSimpleName());
            return isAudioRequest(request) ? audioErrorResponse(ErrorCode.INTERNAL_ERROR) : internalErrorResponse();
        }
        int statusValue = status.value();
        ErrorCode errorCode = ErrorCode.fromHttpStatus(statusValue);
        String message = isAudioRequest(request) ? errorCode.getDefaultMessage() : clientMessage(e, errorCode);
        log.warn("客户端错误 {} {} -> {}: {}", statusValue, e.getClass().getSimpleName(), errorCode.getCode(), message);
        // HTTP 状态用 Spring 给的原始值：406/409 这些枚举里没有的状态不能被压成 400，业务码才走「最近枚举」
        ResponseEntity.BodyBuilder response = ResponseEntity.status(statusValue);
        if (isAudioRequest(request)) {
            response.contentType(MediaType.APPLICATION_JSON);
        }
        return response.body(ApiResponse.error(errorCode, message, null));
    }

    /**
     * 少数几个「Spring 自己判成 400、却没实现 ErrorResponse」的异常。javap 核实过（Spring 6.2.7）：
     * HttpMessageNotReadableException 走 HttpMessageConversionException（NestedRuntimeException 一脉）、
     * MethodArgumentTypeMismatchException 走 TypeMismatchException（beans 一脉），两者都不带状态码 ——
     * 它们的 400 只写在 Spring 自己的 ResponseEntityExceptionHandler 里，这里按同一口径补上。
     * 这一族其余成员（方法不支持 405、Content-Type 不支持 415、缺参数 400、路径变量缺失 500……）
     * 都已实现 ErrorResponse，走上面那条通用分流。
     */
    private static HttpStatusCode legacyClientStatus(Exception e) {
        if (e instanceof HttpMessageNotReadableException || e instanceof MethodArgumentTypeMismatchException) {
            return HttpStatus.BAD_REQUEST;
        }
        return null;
    }

    private static ResponseEntity<ApiResponse<Object>> internalErrorResponse() {
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(), null));
    }

    private static ResponseEntity<ApiResponse<Object>> audioErrorResponse(ErrorCode errorCode) {
        // 显式媒体类型使 Accept: audio/wav 的调用方仍能收到 JSON 错误，而非二次协商成 406。
        return ResponseEntity.status(errorCode.getHttpStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(errorCode, errorCode.getDefaultMessage(), null));
    }

    private static boolean isAudioRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null || contextPath == null || !uri.startsWith(contextPath)) {
            return false;
        }
        String path = uri.substring(contextPath.length());
        return "/api/v1/audio/transcriptions".equals(path) || "/api/v1/audio/speech".equals(path);
    }

    /** 把 Spring 的异常翻译成人能看懂、且**不含请求载荷**的一句话。 */
    private static String clientMessage(Exception e, ErrorCode fallback) {
        if (e instanceof HttpMessageNotReadableException) {
            return "请求体不是合法 JSON，或字段类型不匹配（原始报文不回显）";
        }
        if (e instanceof HttpRequestMethodNotSupportedException method) {
            String[] supported = method.getSupportedMethods() == null ? new String[0] : method.getSupportedMethods();
            return "该路径不支持 " + method.getMethod() + (supported.length == 0 ? "" : "（支持: " + String.join("/", supported) + "）");
        }
        if (e instanceof HttpMediaTypeNotSupportedException media) {
            return "不支持的 Content-Type: " + media.getContentType() + "（支持: " + media.getSupportedMediaTypes() + "）";
        }
        if (e instanceof MissingServletRequestParameterException missing) {
            return "缺少必填参数: " + missing.getParameterName();
        }
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            return "参数 " + mismatch.getName() + " 的值无法解析（要 "
                    + (mismatch.getRequiredType() == null ? "合法取值" : mismatch.getRequiredType().getSimpleName()) + "）";
        }
        return fallback.getDefaultMessage();
    }

    public ResponseEntity<ApiResponse<Object>> handleOther(Exception e) {
        return handleOther(e, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleOther(Exception e, HttpServletRequest request) {
        // Spring 6 起，Spring MVC 的客户端错误大多实现 ErrorResponse 并自带状态码；
        // 注解写不了接口，就在兜底这里分流（见 springClientError / legacyClientStatus 的注释）
        if (e instanceof ErrorResponse errorResponse) {
            return springClientError(e, errorResponse.getStatusCode(), request);
        }
        HttpStatusCode legacyStatus = legacyClientStatus(e);
        if (legacyStatus != null) {
            return springClientError(e, legacyStatus, request);
        }
        if (isAudioRequest(request)) {
            log.error("audio request failed: {}", e.getClass().getSimpleName());
            return audioErrorResponse(ErrorCode.INTERNAL_ERROR);
        }
        log.error("unhandled exception", e);
        return internalErrorResponse();
    }
}
