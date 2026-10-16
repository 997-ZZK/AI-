package com.aiflow.engine.api.service;

import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.persistence.entity.UserEntity;
import com.aiflow.engine.persistence.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserEntity getOrCreate(String externalId, String displayName) {
        return userRepository.findByExternalId(externalId)
                .orElseGet(() -> userRepository.save(
                        new UserEntity(UUID.randomUUID(), externalId, displayName, Instant.now())));
    }

    public UserEntity get(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + userId));
    }
}
