package com.bdreview.platform.review;

import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewService reviewService;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final ReviewPhotoRepository reviewPhotoRepository;

    public ReviewController(ReviewService reviewService, UserRepository userRepository,
                             BusinessRepository businessRepository, ReviewPhotoRepository reviewPhotoRepository) {
        this.reviewService = reviewService;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.reviewPhotoRepository = reviewPhotoRepository;
    }

    @PostMapping("/upload-url")
    public ResponseEntity<PreSignedUploadResponse> requestUploadUrl(@RequestParam String filename) {
        return ResponseEntity.ok(reviewService.requestImageUploadUrl(filename));
    }

    @PostMapping
    public ResponseEntity<ReviewResponse> submit(@Valid @RequestBody SubmitReviewRequest request) {
        Review review = reviewService.submit(CurrentUser.id(), request);
        return ResponseEntity.ok(toResponse(review));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ReviewResponse> edit(@PathVariable UUID id, @Valid @RequestBody UpdateReviewRequest request) {
        Review review = reviewService.edit(CurrentUser.id(), id, request);
        return ResponseEntity.ok(toResponse(review));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        reviewService.delete(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<Void> vote(@PathVariable UUID id, @Valid @RequestBody VoteRequest request) {
        reviewService.vote(CurrentUser.id(), id, request.voteType());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/business/{businessId}")
    public ResponseEntity<PageResponse<ReviewResponse>> listForBusiness(
            @PathVariable UUID businessId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "newest") String sort) {
        var results = reviewService.listForBusiness(businessId, page, size, sort).map(this::toResponse);
        return ResponseEntity.ok(PageResponse.of(results));
    }

    /** Business detail page CTA: the caller's own review for this business, or 204 if they haven't reviewed it. */
    @GetMapping("/business/{businessId}/mine")
    public ResponseEntity<ReviewResponse> myReviewForBusiness(@PathVariable UUID businessId) {
        Review review = reviewService.myReviewFor(businessId, CurrentUser.id());
        return review == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(toResponse(review));
    }

    @GetMapping("/business/{businessId}/dashboard")
    public ResponseEntity<PageResponse<ReviewResponse>> ownerDashboard(
            @PathVariable UUID businessId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Short rating,
            @RequestParam(defaultValue = "false") boolean unrepliedOnly,
            @RequestParam(defaultValue = "false") boolean flaggedOnly) {
        var results = reviewService.ownerDashboardList(CurrentUser.id(), businessId, page, size,
                rating, unrepliedOnly, flaggedOnly).map(this::toResponse);
        return ResponseEntity.ok(PageResponse.of(results));
    }

    /** Public owner reply ("Response from the owner") — owner-only, no moderation step. */
    @PutMapping("/{id}/reply")
    public ResponseEntity<ReviewResponse> reply(@PathVariable UUID id, @Valid @RequestBody ReplyToReviewRequest request) {
        Review review = reviewService.reply(CurrentUser.id(), id, request);
        return ResponseEntity.ok(toResponse(review));
    }

    @DeleteMapping("/{id}/reply")
    public ResponseEntity<ReviewResponse> removeReply(@PathVariable UUID id) {
        Review review = reviewService.removeReply(CurrentUser.id(), id);
        return ResponseEntity.ok(toResponse(review));
    }

    /** Business detail page "Overall rating" bar chart — public, no auth required. */
    @GetMapping("/business/{businessId}/rating-breakdown")
    public ResponseEntity<RatingBreakdownResponse> ratingBreakdown(@PathVariable UUID businessId) {
        return ResponseEntity.ok(reviewService.ratingBreakdown(businessId));
    }

    @GetMapping("/business/{businessId}/rating-trend")
    public ResponseEntity<List<Object[]>> ratingTrend(@PathVariable UUID businessId,
                                                        @RequestParam(defaultValue = "week") String bucket) {
        return ResponseEntity.ok(reviewService.ratingTrend(businessId, bucket));
    }

    @GetMapping("/mine")
    public ResponseEntity<PageResponse<ReviewResponse>> mine(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var results = reviewService.myReviews(CurrentUser.id(), page, size).map(this::toResponse);
        return ResponseEntity.ok(PageResponse.of(results));
    }

    /** Home page "Recent Activity" feed — public, no auth required. */
    @GetMapping("/recent")
    public ResponseEntity<PageResponse<RecentActivityResponse>> recent(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "9") int size) {
        Page<Review> reviews = reviewService.recentActivity(page, size);
        List<Review> content = reviews.getContent();

        List<UUID> businessIds = content.stream().map(Review::getBusinessId).distinct().toList();
        List<UUID> userIds = content.stream().map(Review::getUserId).distinct().toList();
        List<UUID> reviewIds = content.stream().map(Review::getId).toList();

        Map<UUID, Business> businesses = new HashMap<>();
        businessRepository.findAllByIdInWithCategory(businessIds).forEach(b -> businesses.put(b.getId(), b));

        Map<UUID, String> userNames = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> userNames.put(u.getId(), u.getName()));

        Map<UUID, List<String>> photosByReview = new HashMap<>();
        reviewPhotoRepository.findByReviewIdIn(reviewIds).stream().filter(ReviewPhoto::isApproved).forEach(p ->
                photosByReview.computeIfAbsent(p.getReviewId(), k -> new ArrayList<>()).add(p.getUrl()));

        Page<RecentActivityResponse> mapped = reviews.map(r -> {
            Business b = businesses.get(r.getBusinessId());
            return new RecentActivityResponse(
                    r.getId(), r.getBusinessId(),
                    b != null ? b.getName() : null,
                    b != null ? b.getSlug() : null,
                    b != null ? b.getCoverPhotoUrl() : null,
                    b != null ? b.getCategory().getName() : null,
                    r.getUserId(), userNames.get(r.getUserId()),
                    r.getRating(), r.getContent(),
                    photosByReview.getOrDefault(r.getId(), List.of()),
                    r.getUsefulCount(), r.getFunnyCount(), r.getCoolCount(),
                    r.getCreatedAt());
        });
        return ResponseEntity.ok(PageResponse.of(mapped));
    }

    private ReviewResponse toResponse(Review review) {
        List<String> photoUrls = reviewService.photosFor(review.getId()).stream().map(ReviewPhoto::getUrl).toList();
        String userName = userRepository.findById(review.getUserId()).map(u -> u.getName()).orElse(null);
        return ReviewResponse.from(review, photoUrls, userName);
    }
}
