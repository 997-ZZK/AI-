-- 原件留存：把上传的 PDF/图片原件放进对象存储（MinIO），这里只记"存在哪、多大"。
--
-- 为什么不把二进制放进数据库：原件动辄几 MB 到几十 MB，塞进 PG 会把备份、复制、连接池全拖慢，
-- 而它的访问模式和业务数据完全不同（低频、整份读、按 key 取）。对象存储就是为这件事存在的。
--
-- object_key 允许 null 且是**常态**：原件留存默认关，关掉时这两个列全是 null；
-- 打开后如果对象存储临时不可用，入库照旧成功、只是这两列为 null（旁路失败不阻断主流程，
-- 调用方从"有没有 key"就能看出原件留没留住）。
--
-- object_key 上不建唯一索引：一次上传一个对象，键里已含 documentId；将来若要按内容去重
-- （content-addressed 存储），再单独加列与唯一约束，不要在语义模糊的现在提前约束。
alter table documents add column object_key varchar(512);
alter table documents add column object_bytes bigint;

comment on column documents.object_key is '对象存储里的原件键；null = 未留存（能力未开启，或留存失败，失败会记 WARN）';
comment on column documents.object_bytes is '原件字节数';
