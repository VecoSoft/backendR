package com.bdreview.platform.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request bodies for the Phase 2 catalog module endpoints, grouped in one file
 * since each is a tiny record. All optional text is trimmed to {@code null} in
 * {@link CatalogService}; blank never persists.
 */
public final class CatalogRequests {

    private CatalogRequests() {
    }

    public record ServiceOfferingRequest(
            @NotBlank @Size(max = 160) String name,
            String description,
            @Size(max = 80) String priceText,
            /** OFFERING (default) or FACILITY — only GYM uses FACILITY. */
            ServiceSection section
    ) {
    }

    public record TeamMemberRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 160) String role,
            String bio,
            String photoUrl
    ) {
    }

    public record MenuItemRequest(
            @NotBlank @Size(max = 160) String name,
            String description,
            @Size(max = 80) String priceText,
            String photoUrl,
            @Size(max = 80) String menuSection,
            boolean popular
    ) {
    }

    public record FeaturedProductRequest(
            @NotBlank @Size(max = 160) String name,
            String description,
            @Size(max = 80) String priceText,
            String photoUrl
    ) {
    }

    /** New full order for a module list — every current row id, once each. */
    public record ReorderRequest(@NotEmpty List<UUID> orderedIds) {
    }
}
