package com.aiflow.engine.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.HttpUtils;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MinIO（S3 兼容）实现。
 *
 * <p>两处刻意的处理：
 * <ul>
 *   <li>**桶按需创建、只试一次**：启动时不去连对象存储（它可能比应用晚起来），第一次写入时创建，
 *       失败就抛出去让入库链路记 WARN —— 不让一个可选依赖把应用启动卡住。</li>
 *   <li>**"对象不存在"与"存储坏了"分开**：stat 把 NoSuchKey 映射成空 Optional（正常状态），
 *       其余错误照抛 —— 把故障说成"没有"是最难查的一类问题。</li>
 * </ul>
 */
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);
    private static final long MAX_TIMEOUT_MILLIS = Integer.MAX_VALUE;
    private static final Duration MAX_TIMEOUT = Duration.ofMillis(MAX_TIMEOUT_MILLIS);
    private static final String INVALID_TIMEOUT_MESSAGE =
            "对象存储 timeout 必须介于 1ms 和 2147483647ms 之间";

    private final StorageProperties properties;
    private final MinioClient client;
    private final AtomicBoolean bucketChecked = new AtomicBoolean();

    public MinioObjectStorage(StorageProperties properties) {
        this.properties = properties;
        long timeoutMillis = timeoutMillis(properties.timeout());
        // Preserve the SDK's HTTP/TLS defaults; call timeout also covers the returned download stream.
        OkHttpClient httpClient = HttpUtils.newDefaultHttpClient(timeoutMillis, timeoutMillis, timeoutMillis)
                .newBuilder()
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build();
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .httpClient(httpClient, true)
                .build();
    }

    private static long timeoutMillis(Duration timeout) {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(INVALID_TIMEOUT_MESSAGE);
        }
        return timeout.toMillis();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String id() {
        return "minio(" + properties.describe() + ")";
    }

    @Override
    public StoredObject put(String key, byte[] bytes, String contentType) {
        ensureBucket();
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        try {
            String etag = client.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(type)
                    .build()).etag();
            return new StoredObject(key, bytes.length, type, etag);
        } catch (Exception ignored) {
            throw StorageException.writeFailure();
        }
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(key)
                    .build());
            return Optional.of(new StoredObject(key, stat.size(), stat.contentType(), stat.etag()));
        } catch (ErrorResponseException e) {
            if (isNoSuchKey(e)) {
                return Optional.empty();
            }
            throw StorageException.statFailure();
        } catch (Exception ignored) {
            throw StorageException.statFailure();
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(key)
                    .build());
        } catch (ErrorResponseException e) {
            if (isNoSuchKey(e)) {
                throw StorageException.objectNotFound();
            }
            throw StorageException.readFailure();
        } catch (Exception ignored) {
            throw StorageException.readFailure();
        }
    }

    private void ensureBucket() {
        if (bucketChecked.get()) {
            return;
        }
        synchronized (bucketChecked) {
            if (bucketChecked.get()) {
                return;
            }
            try {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                    log.info("对象存储桶已创建");
                }
                bucketChecked.set(true);
            } catch (Exception ignored) {
                throw StorageException.bucketUnavailable();
            }
        }
    }

    private static boolean isNoSuchKey(ErrorResponseException e) {
        String code = e.errorResponse() == null ? null : e.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchBucket".equals(code);
    }
}
