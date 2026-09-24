package com.bdreview.platform.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
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
            ServiceSection section,
            /** Optional — powers slot generation for booking (minutes). */
            @Min(5) @Max(600) Integer durationMinutes,
            /** Optional cleanup/travel time appended after the service (minutes). */
            @Min(0) @Max(240) Integer bufferMinutes
    ) {
    }

    public record TeamMemberRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 160) String role,
            String bio,
            String photoUrl,
            /** Null on create defaults to active; omit on update to leave unchanged is NOT supported — always send the current value. */
            Boolean active
    ) {
    }

    // ---- Staff scheduling (booking — Salon & Beauty) ----------------------

    public record WeeklyScheduleEntryRequest(
            @NotNull DayOfWeek dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            LocalTime breakStart,
            LocalTime breakEnd
    ) {
    }

    public record ReplaceScheduleRequest(@NotNull List<@Valid WeeklyScheduleEntryRequest> days) {
    }

    /** One service a staff member provides, with an optional per-staff duration/buffer override. */
    public record StaffServiceAssignment(
            @NotNull UUID serviceId,
            /** Null means "use the service's own default." */
            @Min(5) @Max(600) Integer durationMinutes,
            @Min(0) @Max(240) Integer bufferMinutes
    ) {
    }

    public record StaffServiceAssignmentsRequest(@NotNull List<@Valid StaffServiceAssignment> assignments) {
    }

    public record TimeOffRequest(
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Size(max = 200) String reason
    ) {
    }

    public record MenuItemRequest(
            @NotBlank @Size(max = 160) String name,
            String description,
            @Size(max = 80) String priceText,
            String photoUrl,
            @Size(max = 80) String menuSection,
            boolean popular,
            /** Commerce (Phase A): optional numeric price + availability / ordering toggles. */
            @DecimalMin("0.0") @Digits(integer = 8, fraction = 2) BigDecimal price,
            Boolean available,
            Boolean orderingEnabled,
            /** Optional "was" price shown struck through; only kept when greater than price. */
            @DecimalMin("0.0") @Digits(integer = 8, fraction = 2) BigDecimal compareAtPrice
    ) {
    }

    public record FeaturedProductRequest(
            @NotBlank @Size(max = 160) String name,
            String description,
            @Size(max = 80) String priceText,
            String photoUrl
    ) {
    }

    /** Business-wide FAQ entry — not category-scoped, unlike the modules above. */
    public record FaqRequest(
            @NotBlank @Size(max = 300) String question,
            @NotBlank String answer
    ) {
    }

    /** New full order for a module list — every current row id, once each. */
    public record ReorderRequest(@NotEmpty List<UUID> orderedIds) {
    }
}
