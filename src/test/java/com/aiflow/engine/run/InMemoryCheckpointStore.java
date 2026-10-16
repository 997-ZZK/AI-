package com.aiflow.engine.run;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 内存检查点（引擎单测用）；真实实现在 persistence/JpaCheckpointStore。 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    @Override
    public UUID save(UUID runId, RunCheckpoint checkpoint) {
        UUID id = UUID.randomUUID();
        entries.put(id, new Entry(runId, checkpoint, false));
        return id;
    }

    @Override
    public void consume(UUID checkpointId) {
        Entry entry = entries.get(checkpointId);
        if (entry != null) {
            entries.put(checkpointId, new Entry(entry.runId, entry.checkpoint, true));
        }
    }

    @Override
    public Optional<LoadedCheckpoint> findLatestPending(UUID runId) {
        LoadedCheckpoint latest = null;
        for (Map.Entry<UUID, Entry> entry : entries.entrySet()) {
            if (entry.getValue().runId.equals(runId) && !entry.getValue().consumed) {
                latest = new LoadedCheckpoint(entry.getKey(), runId, entry.getValue().checkpoint);
            }
        }
        return Optional.ofNullable(latest);
    }

    public int size() {
        return entries.size();
    }

    private record Entry(UUID runId, RunCheckpoint checkpoint, boolean consumed) {
    }
}
