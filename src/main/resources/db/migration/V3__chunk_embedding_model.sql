-- 记录切片向量由哪个 embedding provider 生成。
--
-- 为什么必须有这一列：向量只在「同一个模型」的向量空间里才可比。换了 embedding provider 之后，
-- 旧向量还在表里、还会被召回，两个向量空间之间的余弦没有意义 —— 而且不会报任何错，
-- 属于最危险的一类静默错误。有了这一列，检索只取与当前 provider 一致的切片，
-- 发现不一致时能给出明确的「先重建索引」提示。
--
-- 允许 null：升级前的历史数据不知道是谁生成的，标成 unknown 比猜一个更诚实；
-- 这些行会被检索忽略，直到 reindex 重建。
alter table document_chunks add column embedding_model varchar(128);

create index idx_chunks_kb_model on document_chunks (kb_id, embedding_model);