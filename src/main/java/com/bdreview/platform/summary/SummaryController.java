// SummaryController.java
package com.bdreview.platform.summary;

import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/summary")
public class SummaryController {

    private final BusinessReviewSummaryRepository summaryRepository;
    private final SummaryGenerationService summaryGenerationService;

    public SummaryController(BusinessReviewSummaryRepository summaryRepository,
                             SummaryGenerationService summaryGenerationService) {
        this.summaryRepository = summaryRepository;
        this.summaryGenerationService = summaryGenerationService;
    }

    /** Cached read (spec §15) — never triggers regeneration itself, so profile views stay cheap. */
    @GetMapping
    public ResponseEntity<BusinessReviewSummary> get(@PathVariable UUID businessId) {
        return summaryRepository.findByBusinessId(businessId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("No summary generated yet for this business"));
    }

    /** Manual trigger for ops/testing — normal flow is the automatic every-10-reviews check in ReviewService. */
    @PostMapping("/regenerate")
    public ResponseEntity<Void> regenerate(@PathVariable UUID businessId) {
        summaryGenerationService.regenerateIfDue(businessId);
        return ResponseEntity.accepted().build();
    }
}