package com.aiflow.engine.persistence.profile;

import com.aiflow.engine.memory.longterm.MemoryKeys;
import com.aiflow.engine.persistence.entity.UserMemoryEntity;
import com.aiflow.engine.persistence.repo.UserMemoryRepository;
import com.aiflow.engine.persistence.repo.UserProfileRepository;
import com.aiflow.engine.profile.ProfilePreferencePort;
import com.aiflow.engine.profile.ProfilePreferenceResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 仅查询两个规范槽位；FACT/EXTRACTED 保留给解析器说明忽略原因。 */
@Component
public class JpaProfilePreferenceAdapter implements ProfilePreferencePort {

    private final UserProfileRepository profileRepository;
    private final UserMemoryRepository memoryRepository;

    public JpaProfilePreferenceAdapter(UserProfileRepository profileRepository,
                                       UserMemoryRepository memoryRepository) {
        this.profileRepository = profileRepository;
        this.memoryRepository = memoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot load(UUID userId) {
        boolean enabled = profileRepository.findById(userId)
                .map(profile -> profile.isAggregationEnabled()).orElse(false);
        List<UserMemoryEntity> rows = memoryRepository.findByUserIdAndMemKeyIn(userId, List.of(
                MemoryKeys.normalize(ProfilePreferenceResolver.RESPONSE_STYLE_KEY),
                MemoryKeys.normalize(ProfilePreferenceResolver.MODEL_POLICY_KEY)));
        List<Entry> entries = rows.stream()
                .sorted(Comparator.comparing(UserMemoryEntity::getMemKey)
                        .thenComparing(row -> row.getKind().name()).thenComparing(UserMemoryEntity::getId))
                .map(row -> new Entry(row.getId(), row.getKind(), row.getSource(), row.getMemKey(), row.getValue()))
                .toList();
        return new Snapshot(enabled, entries);
    }
}
