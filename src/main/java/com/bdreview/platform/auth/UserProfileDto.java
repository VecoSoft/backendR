package com.bdreview.platform.auth;

import java.util.UUID;

/** {@code hasLinkedAccount}: whether this account is paired with an opposite-role account (see accountlink.AccountLink) — drives the Personal/Business switcher in the nav. */
public record UserProfileDto(
        UUID id,
        String phoneNumber,
        UserRole role,
        String name,
        String profilePhotoUrl,
        String preferredLanguage,
        boolean hasLinkedAccount) {
}
