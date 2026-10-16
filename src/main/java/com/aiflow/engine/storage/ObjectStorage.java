package com.aiflow.engine.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * 对象存储（原件留存）的最小契约：放、查、取。
 *
 * <p>为什么要有这层接口，而不是到处直接调 MinIO SDK：入库链路只关心"原件留住没有"，
 * 不该关心背后是 S3 还是本地文件。测试里换成内存实现就能验完"存了没 / 取回一致不一致 /
 * 存不上会不会拖垮入库"，真实 MinIO 只留给联调（README 有实测记录）。
 *
 * <p>实现方要做到两件事：**不可用时说清楚**（enabled() 为 false，且报错信息里写明原因）、
 * **失败要抛 {@link StorageException}**（而不是返回 null 让调用方猜）。
 */
public interface ObjectStorage {

    /** 是否真的可用（关掉时为 false；开了但凭证缺失同样算不可用，并在启动时 WARN）。 */
    boolean enabled();

    /** 诊断用标识（不含凭证）。 */
    String id();

    StoredObject put(String key, byte[] bytes, String contentType);

    /** 元数据；对象不存在时返回空（"没有"是正常状态，不是异常）。 */
    Optional<StoredObject> stat(String key);

    /** 读取内容；对象不存在时抛 {@link StorageException}（这里"没有"意味着调用方拿错了 key）。 */
    InputStream open(String key);
}
