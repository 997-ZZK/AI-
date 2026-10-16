package com.aiflow.engine.common.auth;

import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.common.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 解析 {@code Authorization: Bearer <token>} -&gt; {@link IdentityContext}。
 *
 * <p>三条口径（docs/auth-design.md §3.2）：
 * <ul>
 *   <li>没带令牌 -&gt; 匿名继续，要不要 401 由接口上的 {@link RequireIdentity} 决定；</li>
 *   <li>令牌无效 / 头格式不对 -&gt; **401 立刻失败，不降级为匿名**：「你没带令牌」和「你的令牌不认」必须分得开；</li>
 *   <li>覆盖 {@code /api/**} 与 {@code /actuator/**}（{@code /actuator/health} 例外：编排探活要免鉴权），
 *       静态资源不拦 —— 页面本身不含数据，数据都在 /api 后面。</li>
 * </ul>
 *
 * <p>这里直接写响应（不走 {@code @RestControllerAdvice}）：过滤器在 DispatcherServlet 之前，
 * 抛异常只会变成容器错误页。响应体仍用同一套包装，traceId 由 {@code TraceIdFilter}（更早）放进 MDC。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdentityFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final SubjectRegistry registry;
    private final ObjectMapper objectMapper;

    public IdentityFilter(SubjectRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (registry.enabled() && covers(request.getRequestURI())) {
                String header = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (header != null && !header.isBlank()) {
                    String token = bearer(header);
                    if (token == null) {
                        unauthorized(request, response, "Authorization 头只支持 Bearer <token>");
                        return;
                    }
                    Optional<Subject> subject = registry.resolve(token);
                    if (subject.isEmpty()) {
                        unauthorized(request, response, "令牌无效或已停用");
                        return;
                    }
                    IdentityContext.set(subject.get());
                }
            }
            chain.doFilter(request, response);
        } finally {
            IdentityContext.clear();
        }
    }

    private static boolean covers(String path) {
        if (path == null || path.equals("/actuator/health")) {
            return false;
        }
        return path.startsWith("/api/") || path.startsWith("/actuator/");
    }

    /** 只认 {@code Bearer <token>}（scheme 大小写不敏感），其它形态一律拒绝，不做「猜他想用什么」。 */
    private static String bearer(String header) {
        String trimmed = header.strip();
        if (!trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = trimmed.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        log.warn("401 {} {}: {}", request.getMethod(), request.getRequestURI(), message);
        response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(ErrorCode.UNAUTHORIZED, message, null));
    }
}
