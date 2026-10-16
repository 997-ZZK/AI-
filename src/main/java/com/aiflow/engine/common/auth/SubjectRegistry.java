package com.aiflow.engine.common.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 主体名单：装配期校验 + 令牌解析。
 *
 * <p>两条纪律：
 * <ul>
 *   <li>**值只来自外部注入**：环境变量优先，取不到时读 Spring {@link Environment} 的同名属性
 *       （覆盖 {@code -D} 系统属性与 {@code @DynamicPropertySource} 注入 —— 生产只设环境变量，测试走这条注入口）。
 *       令牌值不进代码、不进仓库、不进日志 —— 校验失败时只报**变量名**。</li>
 *   <li>**配置错了就起不来**：开了鉴权却没有主体、变量取不到值、两个主体共用令牌，都是「服务活着但谁都进不来 /
 *       谁都能进来」这类最难查的状态，宁可启动失败。</li>
 * </ul>
 *
 * <p>比较用 {@link MessageDigest#isEqual}（常量时间）且**不提前返回**：命中位置不随令牌内容变化。
 */
@Component
public class SubjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubjectRegistry.class);

    private final boolean enabled;
    private final List<Entry> entries;
    private final Environment environment;

    public SubjectRegistry(AuthProperties properties, Environment environment) {
        this.environment = environment;
        this.enabled = properties.enabled();
        if (!enabled) {
            this.entries = List.of();
            log.warn("鉴权关闭（aiflow.auth.enabled=false）：decidedBy / actor 仍是自报字段，审批人名单不是安全边界"
                    + "（打开方式见 docs/auth-design.md）");
            return;
        }
        List<AuthProperties.SubjectProperties> declared =
                properties.subjects() == null ? List.of() : properties.subjects();
        if (declared.isEmpty()) {
            throw new IllegalStateException("aiflow.auth.enabled=true 但没配任何 subjects："
                    + "空名单等于「谁都不认识」，要么补上主体，要么把 enabled 关掉");
        }
        List<Entry> built = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenTokens = new HashSet<>();
        for (AuthProperties.SubjectProperties declaredSubject : declared) {
            String id = stripToNull(declaredSubject.id());
            if (id == null) {
                throw new IllegalStateException("aiflow.auth.subjects[].id 不能为空");
            }
            if (!seenIds.add(id)) {
                throw new IllegalStateException("aiflow.auth.subjects 里有重复的 id: " + id);
            }
            String tokenEnv = stripToNull(declaredSubject.tokenEnv());
            if (tokenEnv == null) {
                throw new IllegalStateException("主体 " + id + " 没配 token-env（令牌值必须来自外部注入，不进 yml）");
            }
            String token = resolveToken(tokenEnv);
            if (token == null) {
                throw new IllegalStateException("主体 " + id + " 的令牌取不到：环境变量 " + tokenEnv
                        + " 为空（只报变量名，不打印值）");
            }
            if (!seenTokens.add(token)) {
                throw new IllegalStateException("两个主体共用同一个令牌（" + tokenEnv + "）：谁是谁说不清");
            }
            String displayName = stripToNull(declaredSubject.displayName());
            built.add(new Entry(token.getBytes(StandardCharsets.UTF_8), new Subject(id, displayName == null ? id : displayName)));
        }
        this.entries = List.copyOf(built);
        log.info("鉴权开启：{} 个主体（{}）", entries.size(),
                entries.stream().map(entry -> entry.subject().id()).toList());
    }

    public boolean enabled() {
        return enabled;
    }

    /** 令牌 -> 主体。未知令牌返回空，由调用方转 401，**不降级为匿名**。 */
    public Optional<Subject> resolve(String token) {
        if (!enabled || token == null || token.isBlank()) {
            return Optional.empty();
        }
        // 不做大小写 / 空白宽容：宽容就是给爆破留缝。传输层的空白由 IdentityFilter 处理，比较这一层必须严格
        byte[] candidate = token.getBytes(StandardCharsets.UTF_8);
        Subject matched = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.token(), candidate)) {
                matched = entry.subject();
            }
        }
        return Optional.ofNullable(matched);
    }

    /** 环境变量优先，其次 Spring Environment 的同名属性（测试注入用，两条路共用同一份解析）。 */
    private String resolveToken(String name) {
        String value = stripToNull(System.getenv(name));
        return value == null ? stripToNull(environment.getProperty(name)) : value;
    }

    private static String stripToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private record Entry(byte[] token, Subject subject) {
    }
}
