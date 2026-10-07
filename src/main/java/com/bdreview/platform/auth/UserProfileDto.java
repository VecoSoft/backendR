package com.bdreview.platform.auth;

import java.util.UUID;

/** {@code hasLinkedAccount}: whether this account is paired with an opposite-role account (see accountlink.AccountLink) — drives the Personal/Business switcher in the nav. */
public record UserProfileDto(
        UUID id,
        UserRole role,
        String name,
        String profilePhotoUrl,
        String preferredLanguage,
        boolean hasLinkedAccount,
        /** Public "Join Community" pseudonymous handle — null until the setup flow is completed. */
        String communityUsername,
        /** This account's own Community-facing pseudonymous id — compare against a post/comment's author.id for "is this mine", never against {@code id} (see V46's migration comment). */
        UUID communityProfileId,
        /** Separate from profilePhotoUrl — the avatar shown publicly next to u/{communityUsername}, never the real photo. */
        String communityAvatarUrl,
        /** V59: "M"/"F", null until chosen — the owner always sees their own, even when hidden from others. */
        String communityGender,
        /** V59: whether the M/F badge is shown to other people. */
        boolean communityGenderVisible,
        /** V70 sign-in e-mail (null for a phone-only account created before V70). */
        String email,
        boolean emailVerified,
        AuthProvider authProvider,
        /** Whether a password is set (Google-only accounts can add one through "Forgot password"). */
        boolean hasPassword,
        boolean googleLinked,
        /** Phone-only account from before V70: the app asks to add an e-mail (Google or e-mail + password). */
        boolean needsEmail) {
}
