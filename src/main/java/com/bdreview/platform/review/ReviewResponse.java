package com.bdreview.platform.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewResponse(
        UUID id, UUID businessId, UUID userId, String userName, short rating, String content,
        VisibilityStatus visibilityStatus, int usefulCount, int funnyCount, int coolCount,
        boolean editable, List<String> photoUrls, Instant createdAt,
        String ownerReply, Instant ownerRepliedAt
) {
    public static ReviewResponse from(Review r, List<String> photoUrls, String userName) {
        return new ReviewResponse(r.getId(), r.getBusinessId(), r.getUserId(), userName, r.getRating(), r.getContent(),
                r.getVisibilityStatus(), r.getUsefulCount(), r.getFunnyCount(), r.getCoolCount(),
                r.isWithinEditWindow(), photoUrls, r.getCreatedAt(),
                r.getOwnerReply(), r.getOwnerRepliedAt());
    }
}
