package com.bdreview.platform.auth;

import jakarta.validation.constraints.Size;

/** Sets a new password with the 6-digit code from the forgot-password e-mail. */
public record ResetPasswordRequestDto(
        @Size(max = 320) String email,
        @Size(max = 20) String code,
        @Size(max = 200) String password,
        @Size(max = 200) String confirmPassword) {
}
