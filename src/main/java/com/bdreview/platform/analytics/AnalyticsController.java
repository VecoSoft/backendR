package com.bdreview.platform.analytics;

import com.bdreview.platform.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Phase 3 analytics. {@code POST /events} is public and fire-and-forget (202,
 * empty body). {@code GET /analytics} is owner/admin only. Registered as public
 * for POST in {@code auth.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    public record TrackEventRequest(@NotNull BusinessEventType eventType, @Size(max = 64) String sessionId) {
    }

    @PostMapping("/events")
    public ResponseEntity<Void> track(@PathVariable UUID businessId, @Valid @RequestBody TrackEventRequest req) {
        analyticsService.record(businessId, req.eventType(), req.sessionId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/analytics")
    public AnalyticsResponse analytics(@PathVariable UUID businessId,
                                       @RequestParam(defaultValue = "30d") String range) {
        return analyticsService.forOwner(CurrentUser.id(), CurrentUser.hasRole("ADMIN"), businessId, range);
    }
}
