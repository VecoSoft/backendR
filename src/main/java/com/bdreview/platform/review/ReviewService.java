package com.bdreview.platform.review;

import com.bdreview.platform.accountlink.AccountLinkRepository;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.fakereview.FakeReviewAnalysisService;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import com.bdreview.platform.summary.SummaryGenerationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spec §4/§7. The rating-aggregate update on submit/edit/delete is applied
 * synchronously, in the same transaction, via BusinessRepository's atomic
 * UPDATE — the fake-review ML pass and the summary-regeneration check both
 * run afterwards, asynchronously, so neither one blocks review submission
 * (explicit spec requirement).
 */
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewPhotoRepository reviewPhotoRepository;
    private final ReviewVoteRepository reviewVoteRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final AccountLinkRepository accountLinkRepository;
    private final FakeReviewAnalysisService fakeReviewAnalysisService;
    private final SummaryGenerationService summaryGenerationService;
    private final ObjectStorageClient objectStorageClient;
    private final ReviewService self;

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    public ReviewService(ReviewRepository reviewRepository,
                          ReviewPhotoRepository reviewPhotoRepository,
                          ReviewVoteRepository reviewVoteRepository,
                          BusinessRepository businessRepository,
                          UserRepository userRepository,
                          AccountLinkRepository accountLinkRepository,
                          FakeReviewAnalysisService fakeReviewAnalysisService,
                          SummaryGenerationService summaryGenerationService,
                          ObjectStorageClient objectStorageClient,
                          @Lazy ReviewService self) {
        this.reviewRepository = reviewRepository;
        this.reviewPhotoRepository = reviewPhotoRepository;
        this.reviewVoteRepository = reviewVoteRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.accountLinkRepository = accountLinkRepository;
        this.fakeReviewAnalysisService = fakeReviewAnalysisService;
        this.summaryGenerationService = summaryGenerationService;
        this.objectStorageClient = objectStorageClient;
        this.self = self;
    }

    // -----------------------------------------------------------------
    // Image upload — pre-signed direct-to-storage URL, one call per photo
    // (mirrors gallery.BusinessPhotoService / community.CommunityPostService).
    // Any authenticated user may call this — a reviewer isn't the business
    // owner, so BusinessPhotoService's owner-gated endpoint (which the review
    // form used to reuse as a stopgap) always 403'd here.
    // -----------------------------------------------------------------
    public PreSignedUploadResponse requestImageUploadUrl(String filename) {
        String extension = extensionOf(filename);
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Only image files are allowed (jpg, jpeg, png, webp, gif)");
        }
        String key = objectStorageClient.buildObjectKey("review", UUID.randomUUID().toString(), filename);
        return new PreSignedUploadResponse(
                objectStorageClient.presignPutUrl(key), key, objectStorageClient.cdnUrlFor(key));
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }

    @Transactional
    public Review submit(UUID userId, SubmitReviewRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!user.isOtpVerified()) {
            throw new ForbiddenException("Only OTP-verified accounts can submit reviews");
        }
        Business business = businessRepository.findById(request.businessId())
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        if (business.getOwnerUserId().equals(userId)) {
            throw new ForbiddenException("You can't review a business you own");
        }
        if (reviewerOwnsCompetitor(userId, business)) {
            throw new ForbiddenException("You can't review a business that competes with a business you own (same category and area)");
        }

        if (reviewRepository.findByBusinessIdAndUserIdAndDeletedAtIsNull(request.businessId(), userId).isPresent()) {
            throw new BadRequestException("You already reviewed this business — edit your existing review instead");
        }

        Review review = reviewRepository.save(Review.builder()
                .businessId(request.businessId())
                .userId(userId)
                .rating(request.rating())
                .content(request.content())
                .visibilityStatus(VisibilityStatus.RECOMMENDED) // optimistic default; ML pass may downgrade async
                .build());

        if (request.photoUrls() != null) {
            request.photoUrls().forEach(url ->
                    reviewPhotoRepository.save(ReviewPhoto.builder().reviewId(review.getId()).url(url).build()));
        }

        // Fast path: counts immediately, never waits on the ML analysis below.
        businessRepository.applyRatingAggregateDelta(request.businessId(), request.rating(), 1);

        self.runPostSubmitAnalysis(review.getId(), request.businessId());

        return review;
    }

    /**
     * True when the reviewer owns a business (directly, or via their linked
     * consumer<->business account pair — see AccountLink) in the same
     * category and area as the target — a direct competitor, which makes
     * any review of the target inherently self-interested. Checked
     * independently of the "own business" guard above since the two
     * accounts have different ids.
     */
    private boolean reviewerOwnsCompetitor(UUID reviewerUserId, Business target) {
        List<UUID> ownerAccountIds = new ArrayList<>();
        ownerAccountIds.add(reviewerUserId);
        accountLinkRepository.findByConsumerUserId(reviewerUserId)
                .ifPresent(link -> ownerAccountIds.add(link.getBusinessUserId()));

        return ownerAccountIds.stream()
                .flatMap(id -> businessRepository.findByOwnerUserIdAndDeletedAtIsNull(id).stream())
                .anyMatch(owned -> !owned.getId().equals(target.getId())
                        && owned.getCategory().getId().equals(target.getCategory().getId())
                        && owned.getArea().getId().equals(target.getArea().getId()));
    }

    @Async
    public void runPostSubmitAnalysis(UUID reviewId, UUID businessId) {
        fakeReviewAnalysisService.analyzeAndApply(reviewId);
        summaryGenerationService.regenerateIfDue(businessId);
    }

    @Transactional
    public Review edit(UUID userId, UUID reviewId, UpdateReviewRequest request) {
        Review review = getOwnedEditableOrThrow(userId, reviewId);

        short oldRating = review.getRating();
        review.setRating(request.rating());
        review.setContent(request.content());
        Review saved = reviewRepository.save(review);

        if (review.getVisibilityStatus() == VisibilityStatus.RECOMMENDED) {
            businessRepository.applyRatingAggregateDelta(review.getBusinessId(), request.rating() - oldRating, 0);
        }

        self.runPostSubmitAnalysis(reviewId, review.getBusinessId()); // content changed -> re-run signals
        return saved;
    }

    @Transactional
    public void delete(UUID userId, UUID reviewId) {
        Review review = getOwnedEditableOrThrow(userId, reviewId);

        reviewRepository.softDelete(reviewId, Instant.now());

        if (review.getVisibilityStatus() == VisibilityStatus.RECOMMENDED) {
            businessRepository.applyRatingAggregateDelta(review.getBusinessId(), -review.getRating(), -1);
        }
    }

    @Transactional
    public void vote(UUID userId, UUID reviewId, VoteType voteType) {
        reviewRepository.findByIdAndDeletedAtIsNull(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        if (reviewVoteRepository.existsByReviewIdAndUserIdAndVoteType(reviewId, userId, voteType)) {
            reviewVoteRepository.deleteByReviewIdAndUserIdAndVoteType(reviewId, userId, voteType);
            adjustCount(reviewId, voteType, -1);
        } else {
            reviewVoteRepository.save(ReviewVote.builder().reviewId(reviewId).userId(userId).voteType(voteType).build());
            adjustCount(reviewId, voteType, 1);
        }
    }

    private void adjustCount(UUID reviewId, VoteType type, int delta) {
        switch (type) {
            case USEFUL -> reviewRepository.adjustUsefulCount(reviewId, delta);
            case FUNNY -> reviewRepository.adjustFunnyCount(reviewId, delta);
            case COOL -> reviewRepository.adjustCoolCount(reviewId, delta);
        }
    }

    /** §14: consumer-facing list excludes HIDDEN; NOT_RECOMMENDED still shows, de-emphasized, per Yelp-style UX. */
    public Page<Review> listForBusiness(UUID businessId, int page, int size, String sort) {
        Sort sortOrder = switch (sort) {
            case "highest" -> Sort.by(Sort.Direction.DESC, "rating").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "lowest" -> Sort.by(Sort.Direction.ASC, "rating").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> throw new BadRequestException("sort must be 'newest', 'highest', or 'lowest'");
        };
        return reviewRepository.findByBusinessIdAndDeletedAtIsNullAndVisibilityStatusNot(
                businessId, VisibilityStatus.HIDDEN, PageRequest.of(page, PageRequestDefaults.clamp(size), sortOrder));
    }

    /** §7 owner dashboard: full list including NOT_RECOMMENDED, excluding only HIDDEN/soft-deleted handled by repo. */
    public Page<Review> ownerDashboardList(UUID ownerUserId, UUID businessId, int page, int size) {
        getOwnedBusinessOrThrow(ownerUserId, businessId);
        return reviewRepository.findByBusinessIdAndDeletedAtIsNull(
                businessId, PageRequest.of(page, PageRequestDefaults.clamp(size)));
    }

    /**
     * Public owner reply (Google/Yelp-style "Response from the owner") — a direct
     * owner-authenticated write, no moderation step, same as every other owner
     * action in this codebase (e.g. BusinessService#update).
     */
    @Transactional
    public Review reply(UUID ownerUserId, UUID reviewId, ReplyToReviewRequest request) {
        Review review = reviewRepository.findByIdAndDeletedAtIsNull(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        getOwnedBusinessOrThrow(ownerUserId, review.getBusinessId());
        review.setOwnerReply(request.reply().trim());
        review.setOwnerRepliedAt(Instant.now());
        return reviewRepository.save(review);
    }

    @Transactional
    public Review removeReply(UUID ownerUserId, UUID reviewId) {
        Review review = reviewRepository.findByIdAndDeletedAtIsNull(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        getOwnedBusinessOrThrow(ownerUserId, review.getBusinessId());
        review.setOwnerReply(null);
        review.setOwnerRepliedAt(null);
        return reviewRepository.save(review);
    }

    /** Shared by the owner dashboard listing and the reply endpoints — Review only carries businessId, not an owner user id. */
    private Business getOwnedBusinessOrThrow(UUID ownerUserId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!business.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }
        return business;
    }

    public Page<Review> myReviews(UUID userId, int page, int size) {
        return reviewRepository.findByUserIdAndDeletedAtIsNull(userId, PageRequest.of(page, PageRequestDefaults.clamp(size)));
    }

    /** Home page "Recent Activity" feed — newest public reviews across every business. */
    public Page<Review> recentActivity(int page, int size) {
        return reviewRepository.findByDeletedAtIsNullAndVisibilityStatusNotOrderByCreatedAtDesc(
                VisibilityStatus.HIDDEN, PageRequest.of(page, PageRequestDefaults.clamp(size)));
    }

    /** Business detail page "Overall rating" bar chart. */
    public RatingBreakdownResponse ratingBreakdown(UUID businessId) {
        int[] counts = new int[5]; // index 0 = 1-star ... index 4 = 5-star
        for (Object[] row : reviewRepository.ratingBreakdown(businessId, VisibilityStatus.HIDDEN)) {
            int rating = ((Number) row[0]).intValue();
            int count = ((Number) row[1]).intValue();
            if (rating >= 1 && rating <= 5) {
                counts[rating - 1] = count;
            }
        }
        int total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
        return new RatingBreakdownResponse(counts[4], counts[3], counts[2], counts[1], counts[0], total);
    }

    public List<Object[]> ratingTrend(UUID businessId, String bucket) {
        if (!bucket.equals("week") && !bucket.equals("month")) {
            throw new BadRequestException("bucket must be 'week' or 'month'");
        }
        return reviewRepository.ratingTrend(businessId, bucket);
    }

    public List<ReviewPhoto> photosFor(UUID reviewId) {
        return reviewPhotoRepository.findByReviewId(reviewId);
    }

    /** Business detail page CTA: null means the user hasn't reviewed this business yet. */
    public Review myReviewFor(UUID businessId, UUID userId) {
        return reviewRepository.findByBusinessIdAndUserIdAndDeletedAtIsNull(businessId, userId).orElse(null);
    }

    private Review getOwnedEditableOrThrow(UUID userId, UUID reviewId) {
        Review review = reviewRepository.findByIdAndDeletedAtIsNull(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        if (!review.getUserId().equals(userId)) {
            throw new ForbiddenException("You do not own this review");
        }
        if (!review.isWithinEditWindow()) {
            throw new ForbiddenException("The 72-hour edit/delete window has passed");
        }
        return review;
    }
}
