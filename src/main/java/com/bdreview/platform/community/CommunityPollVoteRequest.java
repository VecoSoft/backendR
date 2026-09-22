package com.bdreview.platform.community;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CommunityPollVoteRequest(@NotNull UUID optionId) {
}
