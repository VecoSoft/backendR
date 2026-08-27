package com.bdreview.platform.auth;

import com.bdreview.platform.accountlink.AccountLinkService;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/** Profile read/update for the "my account" surface — login/register/reset stays in AuthService. */
@Service
public class UserService {

    private static final Set<String> ALLOWED_LANGUAGES = Set.of("en", "bn");

    private final UserRepository userRepository;
    private final ObjectStorageClient objectStorageClient;
    private final AccountLinkService accountLinkService;

    public UserService(UserRepository userRepository, ObjectStorageClient objectStorageClient,
                        AccountLinkService accountLinkService) {
        this.userRepository = userRepository;
        this.objectStorageClient = objectStorageClient;
        this.accountLinkService = accountLinkService;
    }

    public UserProfileDto getProfile(UUID userId) {
        return toDto(getOrThrow(userId));
    }

    @Transactional
    public UserProfileDto updateProfile(UUID userId, String name, String preferredLanguage, String profilePhotoUrl) {
        if (!ALLOWED_LANGUAGES.contains(preferredLanguage)) {
            throw new BadRequestException("Unsupported language — use 'en' or 'bn'.");
        }
        User user = getOrThrow(userId);
        user.setName(name);
        user.setPreferredLanguage(preferredLanguage);
        user.setProfilePhotoUrl(profilePhotoUrl);
        return toDto(userRepository.save(user));
    }

    public PreSignedUploadResponse requestPhotoUploadUrl(UUID userId, String filename) {
        String key = objectStorageClient.buildObjectKey("user", userId.toString(), filename);
        return new PreSignedUploadResponse(
                objectStorageClient.presignPutUrl(key), key, objectStorageClient.cdnUrlFor(key));
    }

    private User getOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private UserProfileDto toDto(User user) {
        return new UserProfileDto(user.getId(), user.getPhoneNumber(), user.getRole(),
                user.getName(), user.getProfilePhotoUrl(), user.getPreferredLanguage(),
                accountLinkService.isLinked(user.getId()));
    }
}
