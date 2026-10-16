package com.aiflow.engine.api;

import com.aiflow.engine.api.service.ProfileService;
import com.aiflow.engine.api.service.UserService;
import com.aiflow.engine.common.api.ApiResponse;
import com.aiflow.engine.common.api.ErrorCode;
import com.aiflow.engine.common.exception.BizException;
import com.aiflow.engine.profile.ProfilePreferenceResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/** 画像重置仅停用派生视图；源运行、审计和长期记忆仍保留。 */
@RestController
@RequestMapping("/api/v1/users/{userId}/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final ProfilePreferenceResolver preferences;
    private final UserService users;

    public ProfileController(ProfileService profileService, ProfilePreferenceResolver preferences, UserService users) {
        this.profileService = profileService;
        this.preferences = preferences;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<ProfileService.View> get(@PathVariable UUID userId) {
        return ApiResponse.ok(profileService.get(userId));
    }

    @PutMapping
    public ApiResponse<ProfileService.View> update(@PathVariable UUID userId,
                                                  @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(profileService.update(userId, request.aggregationEnabled(), request.timezone(),
                request.expectedVersion()));
    }

    @DeleteMapping
    public ApiResponse<ProfileService.View> reset(@PathVariable UUID userId,
                                                 @RequestParam long expectedVersion) {
        if (expectedVersion < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "expectedVersion 必须非负");
        }
        return ApiResponse.ok(profileService.reset(userId, expectedVersion));
    }

    @GetMapping("/preview")
    public ApiResponse<ProfilePreferenceResolver.Decision> preview(@PathVariable UUID userId,
            @RequestParam(defaultValue = "inherit") String profilePreference,
            @RequestParam(required = false) String model) {
        users.get(userId);
        String option = profilePreference.strip().toLowerCase(Locale.ROOT);
        if (!"inherit".equals(option) && !"none".equals(option)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "profilePreference 必须为 inherit 或 none");
        }
        return ApiResponse.ok(preferences.resolve(userId, option, model));
    }

    public record UpdateProfileRequest(@NotNull Boolean aggregationEnabled, @NotBlank @Size(max = 64) String timezone,
                                       @NotNull @PositiveOrZero Long expectedVersion) {
    }
}
