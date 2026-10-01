package com.bdreview.platform.community;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.moderation.CommunityPolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pseudonymous "Community username" (Reddit-style u/username) — separate
 * from User#name (the private/account identity). Unique case-insensitively
 * (V33's functional index on LOWER(community_username) enforces this at the
 * DB level too, as a last line of defense against a race between the
 * existsBy check and the save). No existing uniqueness-check convention in
 * the codebase beyond AuthService#register's existsByPhoneNumberAndRole
 * pre-save check — mirrored here.
 */
@Service
public class CommunityUsernameService {

    private static final Pattern VALID_FORMAT = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");

    /** First reserved-word list in the codebase — no existing moderation/reserved-name convention to reuse. */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "jachai", "moderator", "mod", "support", "help",
            "official", "staff", "system", "root", "null", "undefined", "anonymous",
            "deleted", "banned", "everyone", "here", "channel"
    );

    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();
    /** Null in plain unit tests; always present in the running app. */
    private CommunityPolicyService policy;

    public CommunityUsernameService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Autowired(required = false)
    void setPolicy(@Lazy CommunityPolicyService policy) {
        this.policy = policy;
    }

    User currentUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public boolean isAvailable(String candidate) {
        return isValidFormat(candidate) && !isReserved(candidate)
                && !userRepository.existsByCommunityUsernameIgnoreCase(candidate);
    }

    private boolean isValidFormat(String candidate) {
        return candidate != null && VALID_FORMAT.matcher(candidate).matches();
    }

    private boolean isReserved(String candidate) {
        return RESERVED.contains(candidate.toLowerCase(java.util.Locale.ROOT));
    }

    /** A ready-to-use suggestion for the setup modal — "JachaiUser" + 4 random digits, retried on collision. */
    public String suggest() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = "JachaiUser" + (1000 + random.nextInt(9000));
            if (isAvailable(candidate)) {
                return candidate;
            }
        }
        // Astronomically unlikely with a 4-digit space this sparsely used, but never loop forever.
        return "JachaiUser" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * V59: {@code gender} ("M"/"F") is required the first time — a member can't finish setup
     * without it. Once chosen it may be omitted (a rename keeps it) or sent to change it.
     */
    @Transactional
    public String setUsername(UUID userId, String requested, String gender) {
        String normalizedGender = normalizeGender(gender);
        String candidate = requested == null ? "" : requested.trim();
        if (!isValidFormat(candidate)) {
            throw new BadRequestException(
                    "Username must be 3-20 characters: letters, numbers, and underscores only.");
        }
        if (isReserved(candidate)) {
            throw new BadRequestException("That username isn't available — please choose another.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (normalizedGender == null && user.getCommunityGender() == null) {
            throw new BadRequestException("Please choose Male or Female.");
        }
        if (policy != null && user.getCommunityUsername() != null) {
            policy.assertNotRestricted(userId, CommunityPolicyService.Action.PROFILE);
        }
        if (normalizedGender != null) {
            user.setCommunityGender(normalizedGender);
        }
        if (candidate.equalsIgnoreCase(user.getCommunityUsername())) {
            userRepository.save(user); // same username — only the gender may have changed
            return user.getCommunityUsername();
        }
        if (userRepository.existsByCommunityUsernameIgnoreCase(candidate)) {
            throw new BadRequestException("That username is already taken.");
        }
        user.setCommunityUsername(candidate);
        userRepository.save(user);
        return candidate;
    }

    /**
     * V59: pick/change the gender and/or show or hide the badge. Both optional; a member who never
     * chose a gender must send one (they can't hide a badge they don't have yet).
     */
    @Transactional
    public User updateGender(UUID userId, String gender, Boolean visible) {
        String normalizedGender = normalizeGender(gender);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getCommunityUsername() == null) {
            throw new BadRequestException("Set up your Community username first.");
        }
        if (normalizedGender == null && user.getCommunityGender() == null) {
            throw new BadRequestException("Please choose Male or Female.");
        }
        if (normalizedGender != null && !normalizedGender.equals(user.getCommunityGender())) {
            // Changing what others see is a profile edit — same restriction as renaming.
            if (policy != null && user.getCommunityGender() != null) {
                policy.assertNotRestricted(userId, CommunityPolicyService.Action.PROFILE);
            }
            user.setCommunityGender(normalizedGender);
        }
        if (visible != null) {
            user.setCommunityGenderVisible(visible);
        }
        return userRepository.save(user);
    }

    /** "M"/"F" (case-insensitive, also "male"/"female"), null when absent; anything else is a 400. */
    static String normalizeGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return null;
        }
        return switch (gender.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "M", "MALE" -> "M";
            case "F", "FEMALE" -> "F";
            default -> throw new BadRequestException("Gender must be Male or Female.");
        };
    }
}
