package com.bdreview.platform.auth;

import jakarta.validation.constraints.Size;

/** E-mail sign-up (V70). Field rules are checked by CredentialPolicy so each failure has its own code. */
public record RegisterRequestDto(
        @Size(max = 200) String name,
        @Size(max = 320) String email,
        @Size(max = 200) String password,
        @Size(max = 200) String confirmPassword,
        /** "en" or "bn": the language of the e-mails. */
        @Size(max = 10) String language) {
}
