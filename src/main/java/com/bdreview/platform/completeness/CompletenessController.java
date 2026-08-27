package com.bdreview.platform.completeness;

import com.bdreview.platform.common.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Phase 3 — owner/admin only profile completeness for the dashboard. */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}")
public class CompletenessController {

    private final ProfileCompletenessService service;

    public CompletenessController(ProfileCompletenessService service) {
        this.service = service;
    }

    @GetMapping("/completeness")
    public CompletenessResponse completeness(@PathVariable UUID businessId) {
        return service.forOwner(CurrentUser.id(), CurrentUser.hasRole("ADMIN"), businessId);
    }
}
