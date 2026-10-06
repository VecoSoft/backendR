package com.bdreview.platform.fakereview;

import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.review.Review;
import com.bdreview.platform.review.ReviewRepository;
import com.bdreview.platform.review.VisibilityStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates §14: calls the ML service for a single review, persists the
 * per-signal breakdown, and applies the verdict to the review row.
 *
 * IMPORTANT (per spec): this always runs *after* the review's own creation
 * already went through the event-driven rating-aggregate fast path — this
 * service never blocks that path. If the ML verdict pulls the review out of
 * (or back into) the RECOMMENDED bucket, it retracts/re-applies exactly that
 * review's contribution to the business's rating_sum/review_count via the
 * same atomic aggregate update BusinessRepository already exposes — never a
 * read-modify-write on the Business entity.
 */
@Service
public class FakeReviewAnalysisService {

    /** How far back to look for the timing/rating-clustering comparison window. */
    private static final int RECENT_WINDOW_DAYS = 90;

    private final FakeReviewMlClient mlClient;
    private final ReviewRepository reviewRepository;
    private final FakeReviewSignalRepository signalRepository;
    private final BusinessRepository businessRepository;

    public FakeReviewAnalysisService(FakeReviewMlClient mlClient,
                                      ReviewRepository reviewRepository,
                                      FakeReviewSignalRepository signalRepository,
                                      BusinessRepository businessRepository) {
        this.mlClient = mlClient;
        this.reviewRepository = reviewRepository;
        this.signalRepository = signalRepository;
        this.businessRepository = businessRepository;
    }

    /** V65 admin review policy (score thresholds) — setter-injected so the existing constructor (and its unit tests) stay unchanged. */
    private com.bdreview.platform.adminconfig.AdminConfigService adminConfig;

    @org.springframework.beans.factory.annotation.Autowired
    void setAdminConfig(com.bdreview.platform.adminconfig.AdminConfigService adminConfig) {
        this.adminConfig = adminConfig;
    }

    private com.bdreview.platform.adminconfig.ReviewPolicyConfig policy() {
        return adminConfig != null ? adminConfig.reviewPolicy() : new com.bdreview.platform.adminconfig.ReviewPolicyConfig();
    }

    @Transactional
    public void analyzeAndApply(UUID reviewId) {
        Review review = reviewRepository.findByIdAndDeletedAtIsNull(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Review not found or deleted: " + reviewId));

        VisibilityStatus previousStatus = review.getVisibilityStatus();

        Instant since = Instant.now().minus(RECENT_WINDOW_DAYS, ChronoUnit.DAYS);
        List<Review> recent = reviewRepository
                .findByBusinessIdAndDeletedAtIsNull(review.getBusinessId(),
                        org.springframework.data.domain.PageRequest.of(0, 200))
                .stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(since))
                .toList();

        FakeReviewAnalysisRequestDto request = new FakeReviewAnalysisRequestDto(
                toDto(review),
                recent.stream().map(this::toDto).toList()
        );

        FakeReviewAnalysisResponseDto response = mlClient.analyzeBlocking(request);

        for (SignalResultDto signal : response.signals()) {
            signalRepository.save(FakeReviewSignal.builder()
                    .reviewId(reviewId)
                    .signalType(FakeReviewSignalType.valueOf(signal.signalType()))
                    .score((short) signal.score())
                    .detail(signal.detail())
                    .build());
        }

        // V65: the admin's thresholds decide the outcome from the ML score (the ML service's own
        // verdict used the fixed 31/71 bands).
        VisibilityStatus newStatus = statusForScore(response.suspicionScore());
        reviewRepository.applyFakeReviewVerdict(reviewId, (short) response.suspicionScore(), newStatus);

        applyAggregateTransition(review, previousStatus, newStatus);
    }

    /** Score → visibility using Moderation → Review settings (defaults 31 / 71, same as spec §14). */
    public VisibilityStatus statusForScore(int score) {
        var rules = policy();
        if (score >= rules.getHiddenThreshold()) {
            return VisibilityStatus.HIDDEN;
        }
        return score >= rules.getNotRecommendedThreshold() ? VisibilityStatus.NOT_RECOMMENDED : VisibilityStatus.RECOMMENDED;
    }

    /** Only RECOMMENDED reviews count toward average_rating/review_count (spec §14). */
    private void applyAggregateTransition(Review review, VisibilityStatus previous, VisibilityStatus updated) {
        boolean wasCounted = previous == VisibilityStatus.RECOMMENDED;
        boolean nowCounted = updated == VisibilityStatus.RECOMMENDED;

        if (wasCounted && !nowCounted) {
            businessRepository.applyRatingAggregateDelta(review.getBusinessId(), -review.getRating(), -1);
        } else if (!wasCounted && nowCounted) {
            businessRepository.applyRatingAggregateDelta(review.getBusinessId(), review.getRating(), 1);
        }
        // wasCounted == nowCounted (including HIDDEN -> NOT_RECOMMENDED or vice versa): no aggregate change.
    }

    private ReviewInputDto toDto(Review r) {
        return new ReviewInputDto(r.getId(), r.getBusinessId(), r.getRating(), r.getContent(), r.getCreatedAt());
    }
}
