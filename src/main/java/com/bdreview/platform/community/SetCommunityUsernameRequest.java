package com.bdreview.platform.community;

import jakarta.validation.constraints.NotBlank;

public record SetCommunityUsernameRequest(@NotBlank String username) {
}
