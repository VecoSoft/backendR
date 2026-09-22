package com.bdreview.platform.community;

import jakarta.validation.constraints.NotNull;

public record CommunityCommentVoteRequest(@NotNull CommunityPostVoteType voteType) {
}
