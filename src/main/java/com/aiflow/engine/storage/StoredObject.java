package com.aiflow.engine.storage;

/**
 * 对象存储里的一份对象（元数据）。
 *
 * @param key         对象键
 * @param size        字节数
 * @param contentType MIME 类型（未知时为 application/octet-stream）
 * @param etag        对象存储给的校验值（MinIO 给的是 MD5 十六进制，可当"这份原件没被改过"的证据）
 */
public record StoredObject(String key, long size, String contentType, String etag) {
}
