package com.aiflow.engine.storage;

/**
 * 对象存储操作失败。
 *
 * <p>这个异常跨越了对象存储适配器、入库旁路和 HTTP 下载接口，不能携带 SDK
 * 的原始正文。MinIO 的请求 URL、错误正文和认证相关信息都可能出现在 cause 或
 * message 中，因此生产适配器只能使用下面的固定分类构造异常。
 */
public class StorageException extends RuntimeException {

    public enum Kind {
        GENERIC,
        WRITE,
        STAT,
        READ,
        BUCKET,
        NOT_FOUND
    }

    private final Kind kind;

    public StorageException(String message) {
        this(Kind.GENERIC, message);
    }

    /**
     * 保留源码兼容，但刻意丢弃 cause。新代码应使用固定分类工厂方法。
     */
    public StorageException(String message, Throwable ignoredCause) {
        this(Kind.GENERIC, message);
    }

    private StorageException(Kind kind, String message) {
        // Suppressed 异常同样可能携带上游报文；关闭它，保证边界异常不会被追加异常链。
        super(message, null, false, true);
        this.kind = kind;
    }

    public static StorageException writeFailure() {
        return new StorageException(Kind.WRITE, "对象存储写入失败");
    }

    public static StorageException statFailure() {
        return new StorageException(Kind.STAT, "对象存储元信息查询失败");
    }

    public static StorageException readFailure() {
        return new StorageException(Kind.READ, "对象存储读取失败");
    }

    public static StorageException bucketUnavailable() {
        return new StorageException(Kind.BUCKET, "对象存储不可用");
    }

    public static StorageException objectNotFound() {
        return new StorageException(Kind.NOT_FOUND, "原件不存在于对象存储");
    }

    public final Kind kind() {
        return kind;
    }

    public final boolean isNotFound() {
        return kind == Kind.NOT_FOUND;
    }

}
