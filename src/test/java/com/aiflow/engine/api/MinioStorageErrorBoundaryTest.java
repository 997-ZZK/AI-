package com.aiflow.engine.api;

import com.aiflow.engine.storage.MinioObjectStorage;
import com.aiflow.engine.storage.StorageException;
import com.aiflow.engine.storage.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 直接验证 SDK 异常在适配器边界被替换，不依赖网络或真实 MinIO。 */
class MinioStorageErrorBoundaryTest {

    private static final String SENSITIVE = "sdk-body-credential-url-sensitive";

    @Test
    void bucketFailureUsesFixedDiagnosticWithoutOriginalExceptionChain() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(sensitiveFailure());

        assertFixedFailure(() -> storage(client).put("synthetic/object.txt", new byte[]{1}, "text/plain"),
                "对象存储不可用", StorageException.Kind.BUCKET);
    }

    @Test
    void writeFailureUsesFixedDiagnosticWithoutOriginalExceptionChain() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(sensitiveFailure());

        assertFixedFailure(() -> storage(client).put("synthetic/object.txt", new byte[]{1}, "text/plain"),
                "对象存储写入失败", StorageException.Kind.WRITE);
    }

    @Test
    void statFailureUsesFixedDiagnosticWithoutOriginalExceptionChain() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(sensitiveFailure());

        assertFixedFailure(() -> storage(client).stat("synthetic/object.txt"),
                "对象存储元信息查询失败", StorageException.Kind.STAT);
    }

    @Test
    void openFailureUsesFixedDiagnosticWithoutOriginalExceptionChain() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(sensitiveFailure());

        assertFixedFailure(() -> storage(client).open("synthetic/object.txt"),
                "对象存储读取失败", StorageException.Kind.READ);
    }

    @Test
    void safeStorageExceptionsCannotAccumulateSensitiveSuppressedExceptions() {
        StorageException failure = StorageException.readFailure();
        failure.addSuppressed(sensitiveFailure());

        assertThat(failure.getMessage()).isEqualTo("对象存储读取失败");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private static MinioObjectStorage storage(MinioClient client) {
        StorageProperties properties = new StorageProperties(true, "http://127.0.0.1:19000",
                "synthetic-access", "synthetic-secret", "synthetic-bucket", Duration.ofSeconds(1));
        MinioObjectStorage storage = new MinioObjectStorage(properties);
        ReflectionTestUtils.setField(storage, "client", client);
        return storage;
    }

    private static IOException sensitiveFailure() {
        IOException failure = new IOException(SENSITIVE, new IllegalStateException(SENSITIVE));
        failure.addSuppressed(new IllegalStateException(SENSITIVE));
        return failure;
    }

    private static void assertFixedFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                           String expected, StorageException.Kind kind) {
        assertThatThrownBy(action).isInstanceOf(StorageException.class).hasMessage(expected).hasNoCause()
                .satisfies(error -> {
                    assertThat(error.getSuppressed()).isEmpty();
                    assertThat(((StorageException) error).kind()).isEqualTo(kind);
                    assertThat(error.toString()).doesNotContain(SENSITIVE);
                });
    }
}
