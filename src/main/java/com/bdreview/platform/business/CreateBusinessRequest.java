package com.bdreview.platform.business;

import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record CreateBusinessRequest(
        @NotBlank String name,
        @NotNull UUID categoryId,
        @NotNull UUID cityId,
        @NotNull UUID areaId,
        @NotBlank String contactNumber,
        String operatingHours,
        String description,
        String coverPhotoUrl,
        String logoUrl,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotNull PriceTier priceTier,
        List<UUID> attributeIds,

        // "Business presence" (spec Step 4) — all optional. Blank is treated as absent.
        @Size(max = 500) String websiteUrl,
        @Size(max = 20) String whatsappNumber,
        @Email @Size(max = 255) String email,
        @Size(max = 500) String facebookUrl,
        @Size(max = 500) String instagramUrl
) {
}
