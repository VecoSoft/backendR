package com.bdreview.platform.auth;

import com.bdreview.platform.accountlink.AccountLinkService;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.moderation.CommunityPolicyService;
import com.bdreview.platform.gallery.ObjectStorageClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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

    /**
     * Community avatar — deliberately a DIFFERENT upload path than requestPhotoUploadUrl above:
     * the storage key here uses a random id instead of userId, so the resulting URL (which is
     * public, shown next to u/{communityUsername}) can never be used to link back to this
     * account the way a "user/{userId}/..." URL could. Same pattern as CommunityPostService's
     * image uploads.
     */
    public PreSignedUploadResponse requestCommunityAvatarUploadUrl(String filename) {
        String key = objectStorageClient.buildObjectKey("community-avatar", UUID.randomUUID().toString(), filename);
        return new PreSignedUploadResponse(
                objectStorageClient.presignPutUrl(key), key, objectStorageClient.cdnUrlFor(key));
    }

    /** Null in plain unit tests; always present in the running app. */
    private CommunityPolicyService communityPolicy;

    @Autowired(required = false)
    void setCommunityPolicy(@Lazy CommunityPolicyService communityPolicy) {
        this.communityPolicy = communityPolicy;
    }

    @Transactional
    public UserProfileDto updateCommunityAvatar(UUID userId, String communityAvatarUrl) {
        if (communityPolicy != null) {
            // A muted/suspended/banned member can't change their public community identity.
            communityPolicy.assertNotRestricted(userId, CommunityPolicyService.Action.PROFILE);
        }
        User user = getOrThrow(userId);
        user.setCommunityAvatarUrl(communityAvatarUrl);
        return toDto(userRepository.save(user));
    }

    private User getOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private UserProfileDto toDto(User user) {
        boolean linked = accountLinkService.isLinked(user.getId());
        // A business account linked to a personal one is reached through the account switch and
        // signs in through that personal account's e-mail, so it never needs one of its own.
        boolean needsEmail = (user.getEmail() == null || user.getEmailVerifiedAt() == null)
                && !(user.getRole() == UserRole.BUSINESS_OWNER && linked);
        return new UserProfileDto(user.getId(), user.getRole(),
                user.getName(), user.getProfilePhotoUrl(), user.getPreferredLanguage(),
                linked, user.getCommunityUsername(), user.getCommunityProfileId(),
                user.getCommunityAvatarUrl(), user.getCommunityGender(), user.isCommunityGenderVisible(),
                user.getEmail(), user.getEmailVerifiedAt() != null, user.getAuthProvider(), user.getPasswordHash() != null,
                user.getGoogleSub() != null, needsEmail);
    }
}
