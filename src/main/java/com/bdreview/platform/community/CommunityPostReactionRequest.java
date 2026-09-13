package com.bdreview.platform.community;

import jakarta.validation.constraints.NotNull;

public record CommunityPostReactionRequest(@NotNull CommunityPostReactionType reactionType) {
}
