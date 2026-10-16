package com.aiflow.engine.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * 没开原件留存时的实现：说清楚"为什么没有"，而不是抛 NPE。
 *
 * <p>入库链路只在 enabled() 为 true 时才写；读取接口拿到这个实现时给出的是"这项能力没开"
 * （接口层映射成 503），而不是"文档不存在"（404）—— 两者对排障是完全不同的结论。
 */
public class DisabledObjectStorage implements ObjectStorage {

    private final String reason;

    public DisabledObjectStorage(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String id() {
        return "disabled";
    }

    @Override
    public StoredObject put(String key, byte[] bytes, String contentType) {
        throw new StorageException(reason);
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        return Optional.empty();
    }

    @Override
    public InputStream open(String key) {
        throw new StorageException(reason);
    }

    public String reason() {
        return reason;
    }
}
