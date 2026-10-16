package com.aiflow.engine.persistence;

import com.aiflow.engine.persistence.entity.RunCheckpointEntity;
import com.aiflow.engine.persistence.repo.RunCheckpointRepository;
import com.aiflow.engine.run.CheckpointStore;
import com.aiflow.engine.run.RunCheckpoint;
import com.aiflow.engine.run.RunExecutionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class JpaCheckpointStore implements CheckpointStore {

    private final RunCheckpointRepository repository;
    private final ObjectMapper objectMapper;
    private final RunExecutionRegistry executions;

    public JpaCheckpointStore(RunCheckpointRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, new RunExecutionRegistry());
    }

    @Autowired
    public JpaCheckpointStore(RunCheckpointRepository repository, ObjectMapper objectMapper,
                              RunExecutionRegistry executions) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.executions = executions;
    }

    @Override
    public UUID save(UUID runId, RunCheckpoint checkpoint) {
        return executions.write(runId, () -> {
            UUID id = UUID.randomUUID();
            repository.save(new RunCheckpointEntity(id, runId, checkpoint.nodeId(),
                    repository.maxSeq(runId) + 1, objectMapper.valueToTree(checkpoint), Instant.now()));
            return id;
        });
    }

    @Override
    public void consume(UUID checkpointId) {
        repository.findById(checkpointId).ifPresent(found -> executions.write(found.getRunId(), () -> {
            RunCheckpointEntity entity = repository.findById(checkpointId).orElseThrow();
            entity.setConsumedAt(Instant.now());
            repository.save(entity);
        }));
    }

    @Override
    public Optional<LoadedCheckpoint> findLatestPending(UUID runId) {
        return repository.findFirstByRunIdAndConsumedAtIsNullOrderBySeqDesc(runId).map(entity -> {
            try {
                return new LoadedCheckpoint(entity.getId(), runId,
                        objectMapper.treeToValue(entity.getState(), RunCheckpoint.class));
            } catch (JsonProcessingException e) {
                // 解析不了不是「没有检查点」：静默返回 empty 会让运行永远停在挂起状态且无人知道原因
                throw new IllegalStateException("检查点解析失败: " + entity.getId(), e);
            }
        });
    }
}
