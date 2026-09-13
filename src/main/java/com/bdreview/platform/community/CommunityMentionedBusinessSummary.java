package com.bdreview.platform.community;

import java.util.UUID;

public record CommunityMentionedBusinessSummary(
        UUID id, String name, String slug, String logoUrl, boolean verified
) {
}
