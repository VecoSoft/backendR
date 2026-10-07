package com.bdreview.platform.auth;

/** How an account signs in to the app (V70). The admin panel's phone + password + TOTP login is separate. */
public enum AuthProvider {
    /** Google Sign-In only (no password yet; "Forgot password" can add one). */
    GOOGLE,
    /** Email + password only. */
    PASSWORD,
    /** Both: Google linked to an email/password account, or a password added to a Google account. */
    BOTH,
    /** Created before V70 with phone + OTP; asked to add an email (phone login is switched off). */
    PHONE;

    public AuthProvider withGoogle() {
        return this == PASSWORD || this == BOTH ? BOTH : GOOGLE;
    }

    public AuthProvider withPassword() {
        return this == GOOGLE || this == BOTH ? BOTH : PASSWORD;
    }
}
