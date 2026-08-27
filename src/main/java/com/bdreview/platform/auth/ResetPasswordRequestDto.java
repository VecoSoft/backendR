package com.bdreview.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** {@code role} disambiguates which account to reset — a phone number can now back both a CONSUMER and a BUSINESS_OWNER row (see V17 migration). */
public record ResetPasswordRequestDto(
        @NotBlank String phoneNumber,
        @NotBlank String code,
        @NotBlank String newPassword,
        @NotNull UserRole role) {
}
