package com.bdreview.platform.fakereview;

import org.springframework.security.access.prepost.PreAuthorize;
import com.bdreview.platform.common.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Admin-facing view of the per-signal breakdown behind a review's suspicion score (spec §12/§14). */
@RestController
@RequestMapping("/api/v1/admin/fake-review-signals")
@PreAuthorize("hasRole('ADMIN')")
public class FakeReviewController {

    private final FakeReviewSignalRepository signalRepository;
    private final FakeReviewAnalysisService analysisService;

    public FakeReviewController(FakeReviewSignalRepository signalRepository, FakeReviewAnalysisService analysisService) {
        this.signalRepository = signalRepository;
        this.analysisService = analysisService;
    }

    @GetMapping("/{reviewId}")
    public ResponseEntity<List<FakeReviewSignal>> signalsFor(@PathVariable UUID reviewId) {
        CurrentUser.requireRole("ADMIN");
        return ResponseEntity.ok(signalRepository.findByReviewId(reviewId));
    }

    /** Manual re-run — e.g. after an admin edits moderation thresholds/config. */
    @PostMapping("/{reviewId}/re-analyze")
    public ResponseEntity<Void> reAnalyze(@PathVariable UUID reviewId) {
        CurrentUser.requireRole("ADMIN");
        analysisService.analyzeAndApply(reviewId);
        return ResponseEntity.noContent().build();
    }
}
