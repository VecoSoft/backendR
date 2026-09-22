package com.bdreview.platform.community;

import jakarta.validation.constraints.NotNull;

public record CommunityPostVoteRequest(@NotNull CommunityPostVoteType voteType) {
}
