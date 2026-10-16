package com.aiflow.engine.persistence.repo;

import com.aiflow.engine.persistence.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    Optional<DocumentEntity> findByKbIdAndContentHash(String kbId, String contentHash);

    List<DocumentEntity> findByKbIdOrderByCreatedAtDesc(String kbId);

    long countByKbId(String kbId);
}
