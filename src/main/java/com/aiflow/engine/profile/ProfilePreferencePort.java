package com.aiflow.engine.profile;

import com.aiflow.engine.memory.longterm.MemoryKind;
import com.aiflow.engine.memory.longterm.MemorySource;

import java.util.List;
import java.util.UUID;

/** 画像启用状态与显式偏好的只读边界；读取不创建画像、不回写记忆 hits。 */
@FunctionalInterface
public interface ProfilePreferencePort {

    Snapshot load(UUID userId);

    static ProfilePreferencePort disabled() {
        return userId -> new Snapshot(false, List.of());
    }

    record Snapshot(boolean enabled, List<Entry> entries) {
        public Snapshot {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    record Entry(UUID memoryId, MemoryKind kind, MemorySource source, String key, String value) {
    }
}
