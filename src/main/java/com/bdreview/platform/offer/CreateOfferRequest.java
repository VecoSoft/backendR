package com.bdreview.platform.offer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * discountValue/originalPrice/offerPrice are all optional — a BUY_ONE_GET_ONE or FREE_ITEM
 * offer typically has none of them, per the brief's own "don't assume every offer has an
 * original/discounted price" instruction. Validated further in OfferService (verified-business
 * ownership, validFrom &lt; validUntil, discountValue required for the two numeric types).
 */
public record CreateOfferRequest(
        @NotNull UUID businessId,
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
