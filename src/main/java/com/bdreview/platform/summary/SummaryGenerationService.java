package com.bdreview.platform.summary;

import com.bdreview.platform.review.Review;
import com.bdreview.platform.review.ReviewRepository;
import com.bdreview.platform.review.VisibilityStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates §15: only regenerates the cached per-business summary once at
 * least 10 new (non-deleted, non-hidden) reviews have landed since the last
 * generation — this is the LLM-cost-control trigger the spec calls out.
 *
 * Concurrency: {@link BusinessReviewSummaryRepository#findByBusinessIdForUpdate}
 * takes a pessimistic row lock, so if a burst of reviews crosses the
 * threshold at the same time, only the first transaction in actually
 * regenerates — every other concurrent caller re-checks the (now already
 * updated) row inside the lock and no-ops.
 */
@Service
public class SummaryGenerationService {

    private static final int REVIEWS_PER_SUMMARY = 10;
    private static final int MAX_REVIEWS_CONSIDERED = 200;

    private final SummaryMlClient mlClient;
    private final BusinessReviewSummaryRepository summaryRepository;
    private final ReviewRepository reviewRepository;

    public SummaryGenerationService(SummaryMlClient mlClient,
                                    BusinessReviewSummaryRepository summaryRepository,
                                    ReviewRepository reviewRepository) {
        this.mlClient = mlClient;
        this.summaryRepository = summaryRepository;
        this.reviewRepository = reviewRepository;
    }

    /** Call this whenever a review is created/edited/deleted/re-verdicted for a business. */
    @Transactional
    public void regenerateIfDue(UUID businessId) {
        BusinessReviewSummary existing = summaryRepository.findByBusinessIdForUpdate(businessId).orElse(null);

        Instant since = existing != null ? existing.getGeneratedAt() : Instant.EPOCH;
        long newReviewsSinceLastGeneration = reviewRepository
                .countByBusinessIdAndDeletedAtIsNullAndCreatedAtAfter(businessId, since);

        if (existing != null && newReviewsSinceLastGeneration < REVIEWS_PER_SUMMARY) {
            return; // not due yet — someone else's concurrent write already covers this window
        }

        List<Review> reviews = reviewRepository
                .findByBusinessIdAndDeletedAtIsNullAndVisibilityStatusNot(
                        businessId, VisibilityStatus.HIDDEN, PageRequest.of(0, MAX_REVIEWS_CONSIDERED))
                .getContent();

        SummaryGenerationRequestDto request = new SummaryGenerationRequestDto(
                businessId,
                reviews.stream()
                        .map(r -> new SummaryReviewInputDto(r.getId(), r.getRating(), r.getContent()))
                        .toList()
        );

        SummaryGenerationResponseDto response = mlClient.generate(request).block();
        if (response == null || response.summaryText() == null || response.summaryText().isBlank()) {
            // ML service unavailable, or it had nothing usable to summarize
            // (e.g. only rating-only reviews with no text yet) — leave the
            // existing cached summary in place rather than overwriting a
            // real summary (or creating a fake one) with empty/placeholder
            // text that the profile page would otherwise show as if it were
            // a genuine description.
            return;
        }

        Instant now = Instant.now();
        if (existing == null) {
            summaryRepository.save(BusinessReviewSummary.builder()
                    .businessId(businessId)
                    .summaryText(response.summaryText())
                    .reviewCountAtGeneration(reviews.size())
                    .generatedAt(now)
                    .build());
        } else {
            existing.setSummaryText(response.summaryText());
            existing.setReviewCountAtGeneration(reviews.size());
            existing.setGeneratedAt(now);
            summaryRepository.save(existing); // @Version guards this against any concurrent non-locked writer
        }
    }
}