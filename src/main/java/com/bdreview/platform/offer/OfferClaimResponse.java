package com.bdreview.platform.offer;

import java.time.Instant;
import java.util.UUID;

public record OfferClaimResponse(
        UUID id,
        UUID offerId,
        String offerTitle,
        String businessName,
        String redemptionCode,
        OfferClaimStatus status,
        Instant claimedAt,
        Instant redeemedAt
) {
}
