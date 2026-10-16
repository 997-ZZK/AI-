package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.DocumentChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, UUID> {

    /** join fetch 文档，避免 open-in-view=false 下访问标题触发懒加载异常。 */
    @Query("select chunk from DocumentChunkEntity chunk join fetch chunk.document where chunk.kbId = :kbId")
    List<DocumentChunkEntity> findByKbIdWithDocument(@Param("kbId") String kbId);

    /** ANN 命中后按 id 回表：同样 join fetch 文档，避免 open-in-view=false 下访问标题触发懒加载异常。 */
    @Query("select chunk from DocumentChunkEntity chunk join fetch chunk.document where chunk.id in :ids")
    List<DocumentChunkEntity> findAllByIdWithDocument(@Param("ids") Collection<UUID> ids);

    List<DocumentChunkEntity> findByDocumentIdOrderByChunkIndex(UUID documentId);

    long countByDocumentId(UUID documentId);
}
