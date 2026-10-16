-- ANN 规模基准语料：5000 条切片，其中 200 条是「已知最近邻」（针，chunk_index 1..200），其余是噪声。
--
-- 为什么这么造：真实语料下无法知道「标准答案」，就没法测“ANN 有没有漏召回”。这里把答案写死：
--   * 查询词用探针 token（默认 zq），mock embedding（signed hash kernel）把它映射成单维向量 ±e_405；
--   * 针的向量 = -(1.0 - g*0.002) 落在第 405 维，余弦随 g 严格递减（g=1 最近，g=200 最远）；
--   * 噪声向量第 405 维恒为 0，与查询余弦 <= 0，会被 min-score 丢掉。
-- 于是「正确结果」= chunk_index 7,14,...,56（针的编号乘 7），ANN 与精确路径必须给出同一批，召回少了就是 ANN 掉了东西。
-- 针的 chunk_index 故意隔 7 跳：相邻切片会被 mergeAdjacent 合并成一条，那样就看不到 top-k 的明细了。
--
-- 探针常量怎么来的：MockEmbeddingProvider 的 FNV-1a（见 MockEmbeddingProvider#tokenize/#fnv1a），
--   hash = fnv1a("zq") = 0x08f75307b5925d94，dim = floorMod(hash,1024)+1 = 405，sign = ((hash>>>33)&1)=0 ? +1 : -1 = -1。
--   换查询词就要重算这三个数，否则针不指向查询方向。
--
-- 幂等：先清空本 kb（只删这个基准 kb 的数据），再重建。

delete from document_chunks where kb_id = 'e2e-ann-bench';
delete from documents where kb_id = 'e2e-ann-bench';

insert into documents (id, kb_id, title, source_type, content_hash, status, chunk_count, created_at, updated_at)
values ('00000000-0000-0000-0000-0000000a0e01'::uuid, 'e2e-ann-bench', 'bench-ann-5000', 'TEXT',
        'bench-ann-5000', 'INDEXED', 5000, now(), now());

insert into document_chunks (id, document_id, kb_id, chunk_index, content, char_count, content_hash,
                             created_at, embedding, embedding_model)
select gen_random_uuid(),
       '00000000-0000-0000-0000-0000000a0e01'::uuid,
       'e2e-ann-bench',
       r.chunk_index,
       'bench chunk ' || r.g,
       13,
       md5('bench-ann-' || r.g),
       now(),
       r.literal::vector,
       'mock-embedding'
from (
    select b.g, b.chunk_index, b.needle, b.v, b.n1, b.n2,
           (select '[' || string_agg(
                       (case when i = b.needle then b.v
                             when i = b.n1 then 0.25
                             when i = b.n2 then 0.15
                             else 0 end)::text, ',' order by i) || ']'
            from generate_series(1, 1024) i) as literal
    from (
        -- 200 条针：chunk_index = g*7（7,14,...,1400），第 405 维取 -(1.0-g*0.002)
        select g, g * 7 as chunk_index, 405 as needle, -(1.0 - g * 0.002) as v, 704 as n1, 1016 as n2
        from generate_series(1, 200) g
        union all
        -- 4800 条噪声：chunk_index = 2000+n，第 405 维恒为 0，余弦 <= 0，会被阈值丢掉
        select 200 + n, 2000 + n, 405, 0, 1 + (n * 53) % 1024, 1 + (n * 71) % 1024
        from generate_series(1, 4800) n
    ) b
) r;

analyze document_chunks;
