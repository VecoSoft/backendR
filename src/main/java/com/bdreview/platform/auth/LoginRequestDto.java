package com.bdreview.platform.auth;

import jakarta.validation.constraints.Size;

/** {@code context} is optional: omit (or CONSUMER) for the main site, BUSINESS_OWNER opens the linked business account. */
public record LoginRequestDto(@Size(max = 320) String email, @Size(max = 200) String password, UserRole context) {
}
