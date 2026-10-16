package com.aiflow.engine.api;

import com.aiflow.engine.api.service.UserService;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.persistence.entity.UserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserEntity user = userService.getOrCreate(request.externalId(), request.displayName());
        return ApiResponse.ok(toResponse(user));
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserResponse> get(@PathVariable UUID userId) {
        return ApiResponse.ok(toResponse(userService.get(userId)));
    }

    private UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getId(), user.getExternalId(), user.getDisplayName(), user.getStatus(),
                user.getCreatedAt());
    }

    public record CreateUserRequest(@NotBlank String externalId, String displayName) {
    }

    public record UserResponse(UUID id, String externalId, String displayName, String status, Instant createdAt) {
    }
}
