package com.bdreview.platform.community;

import jakarta.validation.constraints.NotBlank;

/** {@code gender}: "M"/"F" (V59) — required the first time a member sets up their username. */
public record SetCommunityUsernameRequest(@NotBlank String username, String gender) {
}
