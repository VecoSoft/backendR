package com.bdreview.platform.offer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Same field set as CreateOfferRequest minus businessId, which never changes after creation. */
public record UpdateOfferRequest(
        @NotBlank @Size(max = 150) String title,
        @NotNull OfferType offerType,
        BigDecimal discountValue,
        BigDecimal originalPrice,
        BigDecimal offerPrice,
        @Size(max = 5000) String description,
        @Size(max = 5000) String termsAndConditions,
        String imageUrl,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil,
        @NotNull OfferAvailability availability,
        Integer maxTotalRedemptions,
        Integer maxRedemptionsPerUser
) {
}
