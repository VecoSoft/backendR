package com.bdreview.platform.auth;

import jakarta.validation.constraints.NotBlank;

/** {@code context} is optional — omit (or CONSUMER) for the main site's login, pass BUSINESS_OWNER to log into a linked business account instead. */
public record LoginRequestDto(@NotBlank String phoneNumber, @NotBlank String password, UserRole context) {
}
