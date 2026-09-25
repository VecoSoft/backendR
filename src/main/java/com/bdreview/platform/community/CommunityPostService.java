package com.bdreview.platform.community;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.common.RateLimitExceededException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import com.bdreview.platform.review.ReviewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * "Join Community" V1 — Reddit-style pseudonymous text discussion (see V33
 * migration; formerly a Facebook-style content+image feed). Members post a
 * title + optional body under their Community username (never their real
 * account name), vote, comment/reply (max depth {@value #MAX_COMMENT_DEPTH}),
 * and can optionally tag one business listing and/or attach up to
 * {@value #MAX_POST_PHOTOS} photos (requestImageUploadUrl issues a
 * pre-signed URL per file; createPost/updatePost persist the resulting CDN
 * URLs into community_post_photo — same direct-to-storage flow as the
 * business gallery, one row per photo like review.ReviewPhoto).
 */
@Service
public class CommunityPostService {

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final int MAX_COMMENT_DEPTH = 5;
    private static final int MIN_POLL_OPTIONS = 2;
    private static final int MAX_POLL_OPTIONS = 6;
    private static final int MAX_POLL_OPTION_LENGTH = 80;
    /** 1 day / 3 days / 7 days — the only durations a poll can run for (see the V1 poll spec). */
    private static final Set<Integer> ALLOWED_POLL_DURATION_HOURS = Set.of(24, 72, 168);
    /** Facebook-style photo grid on the composer/feed/detail page — same ceiling as the business gallery. */
    private static final int MAX_POST_PHOTOS = 10;

    private final CommunityPostRepository postRepository;
    private final CommunityPostVoteRepository voteRepository;
    private final CommunityCommentVoteRepository commentVoteRepository;
    private final CommunityPostCommentRepository commentRepository;
    private final CommunityPostMentionRepository mentionRepository;
    private final CommunityBusinessMentionRepository businessMentionRepository;
    private final CommunityPostPhotoRepository photoRepository;
    private final CommunityFollowRepository followRepository;
    private final CommunityQuestionFollowRepository questionFollowRepository;
    private final CommunityQuestionPassRepository questionPassRepository;
    private final CommunityPostPollRepository pollRepository;
    private final CommunityPostPollOptionRepository pollOptionRepository;
    private final CommunityPostPollVoteRepository pollVoteRepository;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final AreaRepository areaRepository;
    private final ReviewRepository reviewRepository;
    private final ObjectStorageClient objectStorageClient;
    private final CommunityNotifier communityNotifier;
    private final long maxPostsPerHour;
    private final long maxCommentsPerHour;

    public CommunityPostService(CommunityPostRepository postRepository,
                                 CommunityPostVoteRepository voteRepository,
                                 CommunityCommentVoteRepository commentVoteRepository,
                                 CommunityPostCommentRepository commentRepository,
                                 CommunityPostMentionRepository mentionRepository,
                                 CommunityBusinessMentionRepository businessMentionRepository,
                                 CommunityPostPhotoRepository photoRepository,
                                 CommunityFollowRepository followRepository,
                                 CommunityQuestionFollowRepository questionFollowRepository,
                                 CommunityQuestionPassRepository questionPassRepository,
                                 CommunityPostPollRepository pollRepository,
                                 CommunityPostPollOptionRepository pollOptionRepository,
                                 CommunityPostPollVoteRepository pollVoteRepository,
                                 UserRepository userRepository,
                                 BusinessRepository businessRepository,
                                 AreaRepository areaRepository,
                                 ReviewRepository reviewRepository,
                                 ObjectStorageClient objectStorageClient,
                                 CommunityNotifier communityNotifier,
                                 @Value("${app.community.max-posts-per-hour:20}") long maxPostsPerHour,
                                 @Value("${app.community.max-comments-per-hour:60}") long maxCommentsPerHour) {
        this.postRepository = postRepository;
        this.voteRepository = voteRepository;
        this.commentVoteRepository = commentVoteRepository;
        this.commentRepository = commentRepository;
        this.mentionRepository = mentionRepository;
        this.businessMentionRepository = businessMentionRepository;
        this.photoRepository = photoRepository;
        this.followRepository = followRepository;
        this.questionFollowRepository = questionFollowRepository;
        this.questionPassRepository = questionPassRepository;
        this.pollRepository = pollRepository;
        this.pollOptionRepository = pollOptionRepository;
        this.pollVoteRepository = pollVoteRepository;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.areaRepository = areaRepository;
        this.reviewRepository = reviewRepository;
        this.objectStorageClient = objectStorageClient;
        this.communityNotifier = communityNotifier;
        this.maxPostsPerHour = maxPostsPerHour;
        this.maxCommentsPerHour = maxCommentsPerHour;
    }

    // -----------------------------------------------------------------
    // Image upload — pre-signed direct-to-storage URL, one call per photo
    // (mirrors gallery.BusinessPhotoService); the client uploads each file
    // then sends the resulting CDN URLs as CreateCommunityPostRequest#imageUrls.
    // -----------------------------------------------------------------

    public PreSignedUploadResponse requestImageUploadUrl(String filename) {
        String extension = extensionOf(filename);
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Only image files are allowed (jpg, jpeg, png, webp, gif)");
        }
        // A fresh random folder segment, not the uploader's user id — the resulting URL is public
        // (served back as the post's photo), and V46's migration is exactly about not letting a
        // real user id leak through any Community-facing response, including this one.
        String key = objectStorageClient.buildObjectKey("community-post", UUID.randomUUID().toString(), filename);
        return new PreSignedUploadResponse(
                objectStorageClient.presignPutUrl(key), key, objectStorageClient.cdnUrlFor(key));
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // -----------------------------------------------------------------
    // Create / read / update / delete
    // -----------------------------------------------------------------

    @Transactional
    public CommunityPostResponse createPost(UUID userId, CreateCommunityPostRequest request) {
        requireCommunityUsername(userId);
        enforceRateLimit(postRepository.countByAuthorUserIdAndCreatedAtAfter(userId, oneHourAgo()), maxPostsPerHour,
                "You're posting too frequently — please try again later.");
        validatePollRequest(request);
        List<String> photoUrls = normalizedPhotoUrls(request.imageUrls());

        Business business = resolveBusiness(request.businessId());
        UUID resolvedAreaId = request.areaId() != null ? request.areaId()
                : (business != null && business.getArea() != null ? business.getArea().getId() : null);

        CommunityPost post = postRepository.save(CommunityPost.builder()
                .authorUserId(userId)
                .title(blankToNull(request.title()))
                .body(blankToNull(request.body()))
                .postType(request.postType())
                .topic(request.topic())
                .areaId(resolvedAreaId)
                .build());

        savePhotos(post.getId(), photoUrls);

        if (request.postType() == CommunityPostType.POLL) {
            createPoll(post.getId(), request);
        }

        List<UUID> businessIds = business == null ? List.of() : List.of(business.getId());
        saveMentions(post.getId(), businessIds);
        notifyMentionedBusinesses(post, businessIds);
        return toResponse(post, userId);
    }

    // -----------------------------------------------------------------
    // Photo attachments — up to MAX_POST_PHOTOS per post, one row each in
    // community_post_photo (position preserves upload order for the grid).
    // -----------------------------------------------------------------

    private List<String> normalizedPhotoUrls(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> urls = raw.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList();
        if (urls.size() > MAX_POST_PHOTOS) {
            throw new BadRequestException("A post can have at most " + MAX_POST_PHOTOS + " photos");
        }
        return urls;
    }

    private void savePhotos(UUID postId, List<String> photoUrls) {
        List<CommunityPostPhoto> rows = new ArrayList<>();
        for (int i = 0; i < photoUrls.size(); i++) {
            rows.add(CommunityPostPhoto.builder().postId(postId).url(photoUrls.get(i)).position((short) i).build());
        }
        photoRepository.saveAll(rows);
    }

    // -----------------------------------------------------------------
    // Polls — a POLL post's own body is the question; 2-6 single-choice
    // options, auto-closing after a fixed duration (1/3/7 days). Voting is
    // one row per (poll, user), toggled/swapped exactly like post voting.
    // -----------------------------------------------------------------

    private void validatePollRequest(CreateCommunityPostRequest request) {
        if (request.postType() != CommunityPostType.POLL) {
            return;
        }
        List<String> options = normalizedPollOptions(request.pollOptions());
        if (options.size() < MIN_POLL_OPTIONS || options.size() > MAX_POLL_OPTIONS) {
            throw new BadRequestException(
                    "A poll needs between " + MIN_POLL_OPTIONS + " and " + MAX_POLL_OPTIONS + " options");
        }
        if (options.stream().anyMatch(o -> o.length() > MAX_POLL_OPTION_LENGTH)) {
            throw new BadRequestException("Poll option text is too long (max " + MAX_POLL_OPTION_LENGTH + " characters)");
        }
        long distinctCount = options.stream().map(o -> o.toLowerCase(Locale.ROOT)).distinct().count();
        if (distinctCount != options.size()) {
            throw new BadRequestException("Poll options must be unique");
        }
        if (request.pollDurationHours() == null || !ALLOWED_POLL_DURATION_HOURS.contains(request.pollDurationHours())) {
            throw new BadRequestException("Choose how long the poll should run");
        }
    }

    private List<String> normalizedPollOptions(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private void createPoll(UUID postId, CreateCommunityPostRequest request) {
        CommunityPostPoll poll = pollRepository.save(CommunityPostPoll.builder()
                .postId(postId)
                .closesAt(Instant.now().plus(request.pollDurationHours(), ChronoUnit.HOURS))
                .build());
        List<String> options = normalizedPollOptions(request.pollOptions());
        List<CommunityPostPollOption> rows = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            rows.add(CommunityPostPollOption.builder()
                    .pollId(poll.getId()).label(options.get(i)).position((short) i).build());
        }
        pollOptionRepository.saveAll(rows);
    }

    @Transactional
    public CommunityPollResponse votePoll(UUID userId, UUID postId, UUID optionId) {
        postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        CommunityPostPoll poll = pollRepository.findByPostId(postId)
                .orElseThrow(() -> new BadRequestException("This post has no poll"));
        if (poll.isClosed()) {
            throw new BadRequestException("This poll has closed");
        }
        CommunityPostPollOption option = pollOptionRepository.findByIdAndPollId(optionId, poll.getId())
                .orElseThrow(() -> new BadRequestException("Invalid poll option"));

        Optional<CommunityPostPollVote> existing = pollVoteRepository.findByPollIdAndUserId(poll.getId(), userId);
        if (existing.isPresent()) {
            CommunityPostPollVote current = existing.get();
            if (current.getOptionId().equals(option.getId())) {
                pollVoteRepository.deleteByPollIdAndUserId(poll.getId(), userId);
                pollOptionRepository.adjustVoteCount(option.getId(), -1);
            } else {
                pollOptionRepository.adjustVoteCount(current.getOptionId(), -1);
                current.setOptionId(option.getId());
                pollVoteRepository.save(current);
                pollOptionRepository.adjustVoteCount(option.getId(), 1);
            }
        } else {
            pollVoteRepository.save(CommunityPostPollVote.builder()
                    .pollId(poll.getId()).userId(userId).optionId(option.getId()).build());
            pollOptionRepository.adjustVoteCount(option.getId(), 1);
        }

        List<CommunityPostPollOption> options = pollOptionRepository.findByPollIdOrderByPosition(poll.getId());
        UUID myVoteOptionId = pollVoteRepository.findByPollIdAndUserId(poll.getId(), userId)
                .map(CommunityPostPollVote::getOptionId).orElse(null);
        return assemblePoll(poll, options, myVoteOptionId);
    }

    /** Hides per-option counts from a viewer who hasn't voted yet and while the poll is still open. */
    private CommunityPollResponse assemblePoll(CommunityPostPoll poll, List<CommunityPostPollOption> options, UUID myVoteOptionId) {
        boolean closed = poll.isClosed();
        boolean reveal = myVoteOptionId != null || closed;
        int total = options.stream().mapToInt(CommunityPostPollOption::getVoteCount).sum();
        List<CommunityPollOptionResponse> optionResponses = options.stream()
                .map(o -> new CommunityPollOptionResponse(o.getId(), o.getLabel(), reveal ? o.getVoteCount() : null))
                .toList();
        return new CommunityPollResponse(poll.getId(), optionResponses, total, myVoteOptionId, poll.getClosesAt(), closed);
    }

    public CommunityPostResponse getPost(UUID postId, UUID viewerUserId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        return toResponse(post, viewerUserId);
    }

    public PageResponse<CommunityPostResponse> feed(CommunityFeedTab tab, CommunityTopic topic, CommunityPostType postType,
                                                      CommunitySortOrder sort, UUID areaId, int page, int size, UUID viewerUserId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        boolean top = sort == CommunitySortOrder.TOP;
        Page<CommunityPost> posts;
        if (tab == CommunityFeedTab.FOLLOWING) {
            if (viewerUserId == null) {
                throw new ForbiddenException("Log in to see posts from people you follow");
            }
            List<UUID> followedIds = followRepository.findByFollowerUserId(viewerUserId).stream()
                    .map(CommunityFollow::getFollowedUserId).toList();
            posts = followedIds.isEmpty() ? Page.empty(pageable)
                    : postRepository.findAllByAuthorUserIdInAndDeletedAtIsNullOrderByCreatedAtDesc(followedIds, pageable);
        } else if (tab == CommunityFeedTab.NEARBY) {
            if (areaId == null) {
                throw new BadRequestException("Choose an area to see nearby posts");
            }
            posts = postRepository.findAllByAreaIdAndDeletedAtIsNullOrderByCreatedAtDesc(areaId, pageable);
        } else {
            // FOR_YOU (or no tab): no personalization signal exists yet (no follow-topic, no
            // engagement history) — falls back to the plain recent/top feed, optionally
            // narrowed by topic (Category dropdown) and/or postType (All/Questions/Reviews/
            // Discussions tabs) — both optional scalar filters on the same unified query.
            posts = top ? postRepository.findAllByFiltersOrderByScoreDesc(topic, postType, pageable)
                    : postRepository.findAllByFiltersOrderByCreatedAtDesc(topic, postType, pageable);
        }
        return buildPageResponse(posts, viewerUserId);
    }

    public PageResponse<CommunityPostResponse> postsByAuthor(UUID authorCommunityProfileId, int page, int size, UUID viewerUserId) {
        UUID authorUserId = resolveCommunityProfileId(authorCommunityProfileId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<CommunityPost> posts = postRepository.findAllByAuthorUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(authorUserId, pageable);
        return buildPageResponse(posts, viewerUserId);
    }

    public PageResponse<CommunityPostResponse> postsMentioningBusiness(UUID businessId, int page, int size, UUID viewerUserId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<CommunityPost> posts = postRepository.findAllMentioningBusiness(businessId, pageable);
        return buildPageResponse(posts, viewerUserId);
    }

    @Transactional
    public CommunityPostResponse updatePost(UUID userId, UUID postId, UpdateCommunityPostRequest request) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        if (!post.getAuthorUserId().equals(userId)) {
            throw new ForbiddenException("You can only edit your own post");
        }
        post.setTitle(blankToNull(request.title()));
        post.setBody(blankToNull(request.body()));
        post.setTopic(request.topic());
        postRepository.save(post);

        photoRepository.deleteByPostId(postId);
        savePhotos(postId, normalizedPhotoUrls(request.imageUrls()));

        mentionRepository.deleteByPostId(postId);
        List<UUID> businessIds = request.businessId() == null ? List.of() : List.of(request.businessId());
        saveMentions(postId, businessIds);
        return toResponse(post, userId);
    }

    @Transactional
    public void deletePost(UUID userId, UUID postId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        if (!post.getAuthorUserId().equals(userId)) {
            throw new ForbiddenException("You can only delete your own post");
        }
        postRepository.softDelete(postId, Instant.now());
    }

    private Business resolveBusiness(UUID businessId) {
        if (businessId == null) {
            return null;
        }
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new BadRequestException("The attached business could not be found"));
        if (business.isDeleted()) {
            throw new BadRequestException("The attached business could not be found");
        }
        return business;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void saveMentions(UUID postId, List<UUID> businessIds) {
        if (businessIds.isEmpty()) {
            return;
        }
        List<CommunityPostMention> mentions = businessIds.stream()
                .map(businessId -> CommunityPostMention.builder().postId(postId).businessId(businessId).build())
                .toList();
        mentionRepository.saveAll(mentions);
    }

    /** Fired once, on creation only — editing a post's business later does not re-notify. */
    private void notifyMentionedBusinesses(CommunityPost post, List<UUID> businessIds) {
        if (businessIds.isEmpty()) {
            return;
        }
        for (Business business : businessRepository.findAllById(businessIds)) {
            if (business.getOwnerUserId().equals(post.getAuthorUserId())) {
                continue; // don't notify a business owner for tagging their own listing
            }
            communityNotifier.postMentioned(business.getOwnerUserId(), post.getId(), business.getName());
        }
    }

    private Instant oneHourAgo() {
        return Instant.now().minus(1, ChronoUnit.HOURS);
    }

    private void enforceRateLimit(long recentCount, long threshold, String message) {
        if (recentCount >= threshold) {
            throw new RateLimitExceededException(message);
        }
    }

    private User requireCommunityUsername(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getCommunityUsername() == null) {
            throw new BadRequestException("Set up your Community username before posting.");
        }
        return user;
    }

    // -----------------------------------------------------------------
    // Voting — Reddit-style single choice per user, toggled/swapped, same
    // shape the old 6-emoji reaction model already used (see V33: renamed
    // in place rather than rebuilt).
    // -----------------------------------------------------------------

    @Transactional
    public void vote(UUID userId, UUID postId, CommunityPostVoteType type) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));

        Optional<CommunityPostVote> existing = voteRepository.findByPostIdAndUserId(postId, userId);
        if (existing.isPresent()) {
            CommunityPostVote current = existing.get();
            if (current.getVoteType() == type) {
                voteRepository.deleteByPostIdAndUserId(postId, userId);
                adjustPostVoteCount(postId, type, -1);
                return;
            }
            adjustPostVoteCount(postId, current.getVoteType(), -1);
            current.setVoteType(type);
            voteRepository.save(current);
            adjustPostVoteCount(postId, type, 1);
            if (!post.getAuthorUserId().equals(userId)) {
                communityNotifier.newReaction(post.getAuthorUserId(), post.getId());
            }
            return;
        }

        voteRepository.save(CommunityPostVote.builder().postId(postId).userId(userId).voteType(type).build());
        adjustPostVoteCount(postId, type, 1);
        if (!post.getAuthorUserId().equals(userId)) {
            communityNotifier.newReaction(post.getAuthorUserId(), post.getId());
        }
    }

    private void adjustPostVoteCount(UUID postId, CommunityPostVoteType type, int delta) {
        if (type == CommunityPostVoteType.UPVOTE) {
            postRepository.adjustUpvoteCount(postId, delta);
        } else {
            postRepository.adjustDownvoteCount(postId, delta);
        }
    }

    @Transactional
    public void voteComment(UUID userId, UUID commentId, CommunityPostVoteType type) {
        commentRepository.findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));

        Optional<CommunityCommentVote> existing = commentVoteRepository.findByCommentIdAndUserId(commentId, userId);
        if (existing.isPresent()) {
            CommunityCommentVote current = existing.get();
            if (current.getVoteType() == type) {
                commentVoteRepository.deleteByCommentIdAndUserId(commentId, userId);
                adjustCommentVoteCount(commentId, type, -1);
                return;
            }
            adjustCommentVoteCount(commentId, current.getVoteType(), -1);
            current.setVoteType(type);
            commentVoteRepository.save(current);
            adjustCommentVoteCount(commentId, type, 1);
            return;
        }

        commentVoteRepository.save(CommunityCommentVote.builder().commentId(commentId).userId(userId).voteType(type).build());
        adjustCommentVoteCount(commentId, type, 1);
    }

    private void adjustCommentVoteCount(UUID commentId, CommunityPostVoteType type, int delta) {
        if (type == CommunityPostVoteType.UPVOTE) {
            commentRepository.adjustUpvoteCount(commentId, delta);
        } else {
            commentRepository.adjustDownvoteCount(commentId, delta);
        }
    }

    // -----------------------------------------------------------------
    // Comments — threaded replies (flat storage: parentCommentId + depth,
    // capped at MAX_COMMENT_DEPTH; see CommunityPostComment).
    // -----------------------------------------------------------------

    @Transactional
    public CommunityCommentResponse addComment(UUID userId, UUID postId, CreateCommunityCommentRequest request) {
        requireCommunityUsername(userId);
        enforceRateLimit(commentRepository.countByAuthorUserIdAndCreatedAtAfter(userId, oneHourAgo()), maxCommentsPerHour,
                "You're commenting too frequently — please try again later.");

        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));

        short depth = 0;
        CommunityPostComment parent = null;
        if (request.parentCommentId() != null) {
            parent = commentRepository.findByIdAndPostIdAndDeletedAtIsNull(request.parentCommentId(), postId)
                    .orElseThrow(() -> new BadRequestException("The comment you're replying to could not be found"));
            if (parent.getDepth() >= MAX_COMMENT_DEPTH) {
                throw new BadRequestException("This thread is too deep to reply to further");
            }
            depth = (short) (parent.getDepth() + 1);
        } else if (post.getPostType() == CommunityPostType.QUESTION && post.getClosedAt() != null) {
            // A closed question can't get new top-level Answers, but a reply/Comment on an
            // existing Answer is still allowed — same convention Stack Overflow follows.
            throw new BadRequestException("This question is closed to new answers");
        }

        CommunityPostComment comment = commentRepository.save(CommunityPostComment.builder()
                .postId(postId).authorUserId(userId).parentCommentId(request.parentCommentId())
                .depth(depth).content(request.content()).build());
        postRepository.adjustCommentCount(postId, 1);
        if (depth == 0) {
            postRepository.adjustAnswerCount(postId, 1);
        }

        if (parent != null) {
            if (!parent.getAuthorUserId().equals(userId)) {
                communityNotifier.commentReplied(parent.getAuthorUserId(), postId, comment.getId());
            }
        } else if (!post.getAuthorUserId().equals(userId)) {
            communityNotifier.newComment(post.getAuthorUserId(), post.getId());
        }
        return toCommentResponse(comment, userId);
    }

    public PageResponse<CommunityCommentResponse> listComments(UUID postId, int page, int size, UUID viewerUserId) {
        postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size), Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<CommunityPostComment> comments = commentRepository.findByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(postId, pageable);

        // A best-answer comment (if any) sorts first among the top-level (depth 0) items —
        // nested replies stay attached to their parent via parentCommentId regardless of array
        // position, so re-sorting this already-fetched page is a safe pure reorder.
        List<CommunityPostComment> ordered = comments.getContent().stream()
                .sorted(Comparator.comparing((CommunityPostComment c) -> !c.isBestAnswer()))
                .toList();

        List<UUID> commentIds = ordered.stream().map(CommunityPostComment::getId).toList();
        Map<UUID, User> authors = loadAuthors(ordered.stream().map(CommunityPostComment::getAuthorUserId).toList());
        Map<UUID, Long> reviewCounts = loadReviewCounts(authors.keySet());
        Map<UUID, CommunityPostVoteType> myVotes = viewerUserId == null ? Map.of() :
                commentVoteRepository.findByCommentIdInAndUserId(commentIds, viewerUserId).stream()
                        .collect(Collectors.toMap(CommunityCommentVote::getCommentId, CommunityCommentVote::getVoteType));

        List<CommunityCommentResponse> mapped = ordered.stream()
                .map(c -> toCommentResponse(c, authors.get(c.getAuthorUserId()), reviewCounts, myVotes.get(c.getId())))
                .toList();
        return PageResponse.of(new PageImpl<>(mapped, pageable, comments.getTotalElements()));
    }

    /** Community profile page's "Comments" tab. */
    public PageResponse<CommunityCommentResponse> commentsByAuthor(UUID authorCommunityProfileId, int page, int size, UUID viewerUserId) {
        UUID authorUserId = resolveCommunityProfileId(authorCommunityProfileId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<CommunityPostComment> comments = commentRepository.findByAuthorUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(authorUserId, pageable);

        List<UUID> commentIds = comments.getContent().stream().map(CommunityPostComment::getId).toList();
        Map<UUID, User> authors = loadAuthors(comments.getContent().stream().map(CommunityPostComment::getAuthorUserId).toList());
        Map<UUID, Long> reviewCounts = loadReviewCounts(authors.keySet());
        Map<UUID, CommunityPostVoteType> myVotes = viewerUserId == null ? Map.of() :
                commentVoteRepository.findByCommentIdInAndUserId(commentIds, viewerUserId).stream()
                        .collect(Collectors.toMap(CommunityCommentVote::getCommentId, CommunityCommentVote::getVoteType));

        Page<CommunityCommentResponse> mapped = comments.map(c -> toCommentResponse(
                c, authors.get(c.getAuthorUserId()), reviewCounts, myVotes.get(c.getId())));
        return PageResponse.of(mapped);
    }

    @Transactional
    public void deleteComment(UUID userId, UUID postId, UUID commentId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        CommunityPostComment comment = commentRepository.findByIdAndPostIdAndDeletedAtIsNull(commentId, postId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));
        boolean isCommentAuthor = comment.getAuthorUserId().equals(userId);
        boolean isPostAuthor = post.getAuthorUserId().equals(userId);
        if (!isCommentAuthor && !isPostAuthor) {
            throw new ForbiddenException("You can't delete this comment");
        }
        comment.setDeletedAt(Instant.now());
        commentRepository.save(comment);
        postRepository.adjustCommentCount(postId, -1);
        if (comment.getDepth() == 0) {
            postRepository.adjustAnswerCount(postId, -1);
        }
    }

    // -----------------------------------------------------------------
    // Best Answer — question-owner-only, at most one per post (partial
    // unique index in V35 backs the same invariant this enforces at the
    // service layer). Only a top-level Answer (depth 0) is eligible.
    // -----------------------------------------------------------------

    @Transactional
    public CommunityCommentResponse markBestAnswer(UUID userId, UUID postId, UUID commentId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        if (!post.getAuthorUserId().equals(userId)) {
            throw new ForbiddenException("Only the question's author can mark a best answer");
        }
        if (post.getPostType() != CommunityPostType.QUESTION) {
            throw new BadRequestException("Only questions can have a best answer");
        }
        CommunityPostComment comment = commentRepository.findByIdAndPostIdAndDeletedAtIsNull(commentId, postId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer not found"));
        if (comment.getDepth() != 0) {
            throw new BadRequestException("Only a top-level answer can be marked best");
        }

        commentRepository.clearBestAnswer(postId);
        comment.setBestAnswer(true);
        commentRepository.save(comment);

        if (!comment.getAuthorUserId().equals(userId)) {
            communityNotifier.bestAnswerMarked(comment.getAuthorUserId(), postId, commentId);
        }
        return toCommentResponse(comment, userId);
    }

    @Transactional
    public CommunityCommentResponse unmarkBestAnswer(UUID userId, UUID postId, UUID commentId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        if (!post.getAuthorUserId().equals(userId)) {
            throw new ForbiddenException("Only the question's author can unmark a best answer");
        }
        CommunityPostComment comment = commentRepository.findByIdAndPostIdAndDeletedAtIsNull(commentId, postId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer not found"));
        comment.setBestAnswer(false);
        commentRepository.save(comment);
        return toCommentResponse(comment, userId);
    }

    // -----------------------------------------------------------------
    // Open/Closed — question-owner-only. questionStatus itself is derived
    // (see assembleQuestionStatus), closedAt is the only stored bit.
    // -----------------------------------------------------------------

    @Transactional
    public void closeQuestion(UUID userId, UUID postId) {
        CommunityPost post = requireQuestionOwnedBy(userId, postId);
        post.setClosedAt(Instant.now());
        postRepository.save(post);
    }

    @Transactional
    public void reopenQuestion(UUID userId, UUID postId) {
        CommunityPost post = requireQuestionOwnedBy(userId, postId);
        post.setClosedAt(null);
        postRepository.save(post);
    }

    private CommunityPost requireQuestionOwnedBy(UUID userId, UUID postId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        if (!post.getAuthorUserId().equals(userId)) {
            throw new ForbiddenException("Only the question's author can change its status");
        }
        if (post.getPostType() != CommunityPostType.QUESTION) {
            throw new BadRequestException("Only questions have an open/closed status");
        }
        return post;
    }

    // -----------------------------------------------------------------
    // @mention typeahead (business picker while composing a post)
    // -----------------------------------------------------------------

    public List<CommunityMentionedBusinessSummary> searchMentionCandidates(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return businessMentionRepository.searchForMention(query.trim()).stream()
                .map(this::toBusinessSummary)
                .toList();
    }

    // -----------------------------------------------------------------
    // "Questions for you" widget — following/passing a specific QUESTION post
    // (see CommunityQuestionFollow/CommunityQuestionPass; distinct from the
    // user-follows-user CommunityFollow below) and the recommendation list itself.
    // -----------------------------------------------------------------

    private static final int RECOMMENDED_QUESTIONS_LIMIT = 8;
    /** Cards truncate a title-less question's body to this many characters for the headline. */
    private static final int QUESTION_HEADLINE_MAX_LENGTH = 140;

    @Transactional
    public void followQuestion(UUID userId, UUID postId) {
        requireQuestionPost(postId);
        if (questionFollowRepository.existsByUserIdAndPostId(userId, postId)) {
            return;
        }
        questionFollowRepository.save(CommunityQuestionFollow.builder().userId(userId).postId(postId).build());
    }

    @Transactional
    public void unfollowQuestion(UUID userId, UUID postId) {
        questionFollowRepository.deleteByUserIdAndPostId(userId, postId);
    }

    /** No undo in V1 — matches Quora's Pass, a quiet "don't show me this one again." */
    @Transactional
    public void passQuestion(UUID userId, UUID postId) {
        requireQuestionPost(postId);
        if (questionPassRepository.existsByUserIdAndPostId(userId, postId)) {
            return;
        }
        questionPassRepository.save(CommunityQuestionPass.builder().userId(userId).postId(postId).build());
    }

    private CommunityPost requireQuestionPost(UUID postId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        if (post.getPostType() != CommunityPostType.QUESTION) {
            throw new BadRequestException("Only questions can be followed or passed");
        }
        return post;
    }

    /**
     * Open questions this viewer hasn't asked, isn't already following/passed on, and
     * hasn't already answered — ranked by topic affinity (topics the viewer themselves
     * posts in) first, then most recent. A brand-new viewer with no post history yet
     * gets every topic as their "affinity" set, which makes that ranking a no-op and
     * falls through to pure recency.
     */
    public List<CommunityQuestionRecommendationResponse> recommendedQuestions(UUID viewerId) {
        List<CommunityTopic> authoredTopics = postRepository.findDistinctTopicsByAuthor(viewerId);
        Collection<CommunityTopic> affinityTopics = authoredTopics.isEmpty()
                ? EnumSet.allOf(CommunityTopic.class)
                : EnumSet.copyOf(authoredTopics);
        List<CommunityPost> candidates = postRepository.findRecommendedQuestions(
                CommunityPostType.QUESTION, viewerId, affinityTopics, PageRequest.of(0, RECOMMENDED_QUESTIONS_LIMIT));
        return candidates.stream().map(this::toQuestionRecommendationResponse).toList();
    }

    private CommunityQuestionRecommendationResponse toQuestionRecommendationResponse(CommunityPost post) {
        long followerCount = questionFollowRepository.countByPostId(post.getId());
        Instant lastFollowedAt = questionFollowRepository.findTopByPostIdOrderByCreatedAtDesc(post.getId())
                .map(CommunityQuestionFollow::getCreatedAt)
                .orElse(null);
        String headline = post.getTitle() != null && !post.getTitle().isBlank()
                ? post.getTitle()
                : truncate(post.getBody(), QUESTION_HEADLINE_MAX_LENGTH);
        return new CommunityQuestionRecommendationResponse(
                post.getId(), headline, post.getAnswerCount(), followerCount, lastFollowedAt);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength).trim() + "…" : trimmed;
    }

    // -----------------------------------------------------------------
    // Following — minimal user-follows-user (see CommunityFollow).
    // -----------------------------------------------------------------

    @Transactional
    public void follow(UUID followerUserId, UUID followedCommunityProfileId) {
        UUID followedUserId = resolveCommunityProfileId(followedCommunityProfileId);
        if (followerUserId.equals(followedUserId)) {
            throw new BadRequestException("You can't follow yourself");
        }
        if (followRepository.existsByFollowerUserIdAndFollowedUserId(followerUserId, followedUserId)) {
            return;
        }
        followRepository.save(CommunityFollow.builder()
                .followerUserId(followerUserId).followedUserId(followedUserId).build());
    }

    @Transactional
    public void unfollow(UUID followerUserId, UUID followedCommunityProfileId) {
        UUID followedUserId = resolveCommunityProfileId(followedCommunityProfileId);
        followRepository.deleteByFollowerUserIdAndFollowedUserId(followerUserId, followedUserId);
    }

    // -----------------------------------------------------------------
    // Community profile ("u/username") — public-safe, no real identity.
    // -----------------------------------------------------------------

    public CommunityProfileResponse getProfile(String username, UUID viewerUserId) {
        User user = userRepository.findByCommunityUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResourceNotFoundException("Community profile not found"));
        long reviewCount = reviewRepository.countByUserIdAndDeletedAtIsNull(user.getId());
        long postCount = postRepository.countByAuthorUserIdAndDeletedAtIsNull(user.getId());
        long commentCount = commentRepository.countByAuthorUserIdAndDeletedAtIsNull(user.getId());
        boolean isFollowing = viewerUserId != null
                && followRepository.existsByFollowerUserIdAndFollowedUserId(viewerUserId, user.getId());
        long followerCount = followRepository.countByFollowedUserId(user.getId());
        long followingCount = followRepository.countByFollowerUserId(user.getId());
        return new CommunityProfileResponse(user.getCommunityProfileId(), user.getCommunityUsername(), user.getCreatedAt(),
                user.isOtpVerified(), reviewCount, postCount, commentCount, isFollowing, followerCount, followingCount,
                user.getCommunityAvatarUrl());
    }

    /** Sidebar "Following" — people the given user follows (not their posts; see the profile page's own Follow button for the post-feed equivalent, feed tab=FOLLOWING). */
    public PageResponse<CommunityFollowListItem> following(UUID subjectCommunityProfileId, int page, int size, UUID viewerUserId) {
        UUID subjectUserId = resolveCommunityProfileId(subjectCommunityProfileId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<CommunityFollow> follows = followRepository.findByFollowerUserId(subjectUserId, pageable);
        return toFollowListResponse(follows.map(CommunityFollow::getFollowedUserId), viewerUserId);
    }

    /** Sidebar "Followers" — people who follow the given user. */
    public PageResponse<CommunityFollowListItem> followers(UUID subjectCommunityProfileId, int page, int size, UUID viewerUserId) {
        UUID subjectUserId = resolveCommunityProfileId(subjectCommunityProfileId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<CommunityFollow> follows = followRepository.findByFollowedUserId(subjectUserId, pageable);
        return toFollowListResponse(follows.map(CommunityFollow::getFollowerUserId), viewerUserId);
    }

    /** Translates a Community-facing pseudonymous id back to the real user id — see V46's migration comment. */
    private UUID resolveCommunityProfileId(UUID communityProfileId) {
        return userRepository.findByCommunityProfileId(communityProfileId)
                .map(User::getId)
                .orElseThrow(() -> new ResourceNotFoundException("Community profile not found"));
    }

    private PageResponse<CommunityFollowListItem> toFollowListResponse(Page<UUID> userIds, UUID viewerUserId) {
        List<UUID> ids = userIds.getContent();
        Map<UUID, User> users = loadAuthors(ids);
        Map<UUID, Long> reviewCounts = loadReviewCounts(users.keySet());
        Set<UUID> viewerFollows = viewerUserId == null ? Set.of() :
                followRepository.findByFollowerUserIdAndFollowedUserIdIn(viewerUserId, ids).stream()
                        .map(CommunityFollow::getFollowedUserId).collect(Collectors.toSet());
        Page<CommunityFollowListItem> mapped = userIds.map(id -> new CommunityFollowListItem(
                toAuthorSummary(users.get(id), id, reviewCounts), viewerFollows.contains(id)));
        return PageResponse.of(mapped);
    }

    // -----------------------------------------------------------------
    // Response assembly
    // -----------------------------------------------------------------

    private PageResponse<CommunityPostResponse> buildPageResponse(Page<CommunityPost> posts, UUID viewerUserId) {
        List<CommunityPost> content = posts.getContent();
        Map<UUID, User> authors = loadAuthors(content.stream().map(CommunityPost::getAuthorUserId).toList());
        Map<UUID, List<CommunityPostMention>> mentionsByPost = mentionRepository
                .findByPostIdIn(content.stream().map(CommunityPost::getId).toList())
                .stream().collect(Collectors.groupingBy(CommunityPostMention::getPostId));
        Map<UUID, Business> businessesById = loadBusinesses(mentionsByPost.values().stream()
                .flatMap(List::stream).map(CommunityPostMention::getBusinessId).distinct().toList());
        Map<UUID, CommunityPostVoteType> myVotes = viewerUserId == null ? Map.of() :
                voteRepository.findByPostIdInAndUserId(content.stream().map(CommunityPost::getId).toList(), viewerUserId)
                        .stream().collect(Collectors.toMap(CommunityPostVote::getPostId, CommunityPostVote::getVoteType));
        Map<UUID, Long> reviewCounts = loadReviewCounts(authors.keySet());
        Map<UUID, CommunityAreaSummary> areasById = loadAreas(content.stream()
                .map(CommunityPost::getAreaId).filter(Objects::nonNull).toList());
        Map<UUID, CommunityPollResponse> pollsByPostId = loadPolls(
                content.stream().filter(p -> p.getPostType() == CommunityPostType.POLL).map(CommunityPost::getId).toList(),
                viewerUserId);
        Set<UUID> answeredPostIds = loadPostIdsWithBestAnswer(content.stream()
                .filter(p -> p.getPostType() == CommunityPostType.QUESTION).map(CommunityPost::getId).toList());
        Map<UUID, List<String>> photosByPost = batchLoadPhotos(content);

        Page<CommunityPostResponse> mapped = posts.map(post -> assembleResponse(
                post, authors.get(post.getAuthorUserId()),
                mentionsByPost.getOrDefault(post.getId(), List.of()), businessesById,
                myVotes.get(post.getId()), reviewCounts,
                // Map.of()'s get() throws NPE on a null key (unlike HashMap), and most posts
                // have no areaId — guard rather than let an empty/immutable map crash the feed.
                post.getAreaId() == null ? null : areasById.get(post.getAreaId()),
                pollsByPostId.get(post.getId()),
                answeredPostIds.contains(post.getId()),
                photosByPost.getOrDefault(post.getId(), List.of())));
        return PageResponse.of(mapped);
    }

    private CommunityPostResponse toResponse(CommunityPost post, UUID viewerUserId) {
        User author = userRepository.findById(post.getAuthorUserId()).orElse(null);
        List<CommunityPostMention> mentions = mentionRepository.findByPostId(post.getId());
        Map<UUID, Business> businessesById = loadBusinesses(mentions.stream().map(CommunityPostMention::getBusinessId).toList());
        CommunityPostVoteType myVote = viewerUserId == null ? null :
                voteRepository.findByPostIdAndUserId(post.getId(), viewerUserId)
                        .map(CommunityPostVote::getVoteType).orElse(null);
        Map<UUID, Long> reviewCounts = loadReviewCounts(List.of(post.getAuthorUserId()));
        CommunityAreaSummary area = post.getAreaId() == null ? null : loadAreas(List.of(post.getAreaId())).get(post.getAreaId());
        CommunityPollResponse poll = post.getPostType() != CommunityPostType.POLL ? null :
                pollRepository.findByPostId(post.getId())
                        .map(p -> assemblePoll(p, pollOptionRepository.findByPollIdOrderByPosition(p.getId()),
                                viewerUserId == null ? null : pollVoteRepository.findByPollIdAndUserId(p.getId(), viewerUserId)
                                        .map(CommunityPostPollVote::getOptionId).orElse(null)))
                        .orElse(null);
        boolean hasBestAnswer = post.getPostType() == CommunityPostType.QUESTION
                && commentRepository.findBestAnswerByPostId(post.getId()).isPresent();
        return assembleResponse(post, author, mentions, businessesById, myVote, reviewCounts, area, poll, hasBestAnswer,
                loadPhotos(post));
    }

    /** Ordered photo URLs for one post; a legacy pre-V1 post with no community_post_photo rows falls back to its single legacy image_url. */
    private List<String> loadPhotos(CommunityPost post) {
        List<String> urls = photoRepository.findByPostIdOrderByPositionAsc(post.getId()).stream()
                .map(CommunityPostPhoto::getUrl).toList();
        return urls.isEmpty() && post.getImageUrl() != null ? List.of(post.getImageUrl()) : urls;
    }

    /** Batched post-id -> ordered photo URLs for a feed page — avoids one query per post. */
    private Map<UUID, List<String>> batchLoadPhotos(List<CommunityPost> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> byPostId = photoRepository.findByPostIdIn(posts.stream().map(CommunityPost::getId).toList())
                .stream().collect(Collectors.groupingBy(CommunityPostPhoto::getPostId,
                        Collectors.collectingAndThen(Collectors.toList(), CommunityPostService::sortedPhotoUrls)));
        for (CommunityPost post : posts) {
            if (!byPostId.containsKey(post.getId()) && post.getImageUrl() != null) {
                byPostId.put(post.getId(), List.of(post.getImageUrl()));
            }
        }
        return byPostId;
    }

    private static List<String> sortedPhotoUrls(List<CommunityPostPhoto> photos) {
        return photos.stream().sorted(Comparator.comparing(CommunityPostPhoto::getPosition))
                .map(CommunityPostPhoto::getUrl).toList();
    }

    /** Batched "does this QUESTION post have a best answer" lookup — skipped entirely when the page has no QUESTION posts. */
    private Set<UUID> loadPostIdsWithBestAnswer(List<UUID> questionPostIds) {
        if (questionPostIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(commentRepository.findPostIdsWithBestAnswer(questionPostIds));
    }

    /** Batched poll (+options, +viewer's vote) lookup for a page of posts — skipped entirely when the page has no POLL posts. */
    private Map<UUID, CommunityPollResponse> loadPolls(List<UUID> pollPostIds, UUID viewerUserId) {
        if (pollPostIds.isEmpty()) {
            return Map.of();
        }
        List<CommunityPostPoll> polls = pollRepository.findByPostIdIn(pollPostIds);
        List<UUID> pollIds = polls.stream().map(CommunityPostPoll::getId).toList();
        Map<UUID, List<CommunityPostPollOption>> optionsByPollId = pollOptionRepository.findByPollIdInOrderByPosition(pollIds)
                .stream().collect(Collectors.groupingBy(CommunityPostPollOption::getPollId));
        Map<UUID, UUID> myVoteByPollId = viewerUserId == null ? Map.of() :
                pollVoteRepository.findByPollIdInAndUserId(pollIds, viewerUserId).stream()
                        .collect(Collectors.toMap(CommunityPostPollVote::getPollId, CommunityPostPollVote::getOptionId));
        return polls.stream().collect(Collectors.toMap(CommunityPostPoll::getPostId,
                p -> assemblePoll(p, optionsByPollId.getOrDefault(p.getId(), List.of()), myVoteByPollId.get(p.getId()))));
    }

    private CommunityPostResponse assembleResponse(CommunityPost post, User author,
                                                     List<CommunityPostMention> mentions,
                                                     Map<UUID, Business> businessesById,
                                                     CommunityPostVoteType myVote,
                                                     Map<UUID, Long> reviewCounts,
                                                     CommunityAreaSummary area,
                                                     CommunityPollResponse poll,
                                                     boolean hasBestAnswer,
                                                     List<String> imageUrls) {
        List<CommunityMentionedBusinessSummary> mentionSummaries = mentions.stream()
                .map(m -> businessesById.get(m.getBusinessId()))
                .filter(Objects::nonNull)
                .map(this::toBusinessSummary)
                .toList();

        return new CommunityPostResponse(
                post.getId(),
                toAuthorSummary(author, post.getAuthorUserId(), reviewCounts),
                post.getTitle(),
                post.getBody(),
                imageUrls,
                post.getPostType(),
                post.getTopic(),
                area,
                post.getUpvoteCount(), post.getDownvoteCount(), post.getScore(),
                myVote,
                post.getCommentCount(),
                mentionSummaries,
                poll,
                questionStatus(post, hasBestAnswer),
                post.getAnswerCount(),
                post.getCreatedAt(), post.getUpdatedAt());
    }

    /** Derived, not stored — see CommunityQuestionStatus and V35's migration comment. */
    private CommunityQuestionStatus questionStatus(CommunityPost post, boolean hasBestAnswer) {
        if (post.getPostType() != CommunityPostType.QUESTION) {
            return null;
        }
        if (post.getClosedAt() != null) {
            return CommunityQuestionStatus.CLOSED;
        }
        return hasBestAnswer ? CommunityQuestionStatus.RESOLVED : CommunityQuestionStatus.OPEN;
    }

    private CommunityCommentResponse toCommentResponse(CommunityPostComment comment, UUID viewerUserId) {
        User author = userRepository.findById(comment.getAuthorUserId()).orElse(null);
        Map<UUID, Long> reviewCounts = loadReviewCounts(List.of(comment.getAuthorUserId()));
        CommunityPostVoteType myVote = viewerUserId == null ? null :
                commentVoteRepository.findByCommentIdAndUserId(comment.getId(), viewerUserId)
                        .map(CommunityCommentVote::getVoteType).orElse(null);
        return toCommentResponse(comment, author, reviewCounts, myVote);
    }

    private CommunityCommentResponse toCommentResponse(CommunityPostComment comment, User author,
                                                         Map<UUID, Long> reviewCounts, CommunityPostVoteType myVote) {
        return new CommunityCommentResponse(
                comment.getId(),
                toAuthorSummary(author, comment.getAuthorUserId(), reviewCounts),
                comment.getContent(),
                comment.getParentCommentId(),
                comment.getDepth(),
                comment.isBestAnswer(),
                comment.getUpvoteCount(), comment.getDownvoteCount(), comment.getScore(),
                myVote,
                comment.getCreatedAt(), comment.getUpdatedAt());
    }

    /** Never includes real name/phone/real profile photo, or the real user id — see CommunityAuthorSummary. */
    private CommunityAuthorSummary toAuthorSummary(User user, UUID fallbackId, Map<UUID, Long> reviewCounts) {
        if (user == null) {
            // No row to read a communityProfileId off of (deleted/missing account) — fallbackId
            // here is the post/comment's own stored author_user_id, a dead-end lookup either way.
            return new CommunityAuthorSummary(fallbackId, null, 0, null, false, null);
        }
        long reviewCount = reviewCounts.getOrDefault(user.getId(), 0L);
        return new CommunityAuthorSummary(
                user.getCommunityProfileId(), user.getCommunityUsername(),
                reviewCount, user.getCreatedAt(), user.isOtpVerified(), user.getCommunityAvatarUrl());
    }

    private CommunityMentionedBusinessSummary toBusinessSummary(Business business) {
        return new CommunityMentionedBusinessSummary(
                business.getId(), business.getName(), business.getSlug(), business.getLogoUrl(), business.isVerified());
    }

    private Map<UUID, User> loadAuthors(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds.stream().distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));
    }

    private Map<UUID, Business> loadBusinesses(List<UUID> businessIds) {
        if (businessIds.isEmpty()) {
            return Map.of();
        }
        return businessRepository.findAllById(businessIds.stream().distinct().toList())
                .stream().collect(Collectors.toMap(Business::getId, b -> b));
    }

    /** Trust-signal review counts, batched per page (one grouped query, not one per author). */
    private Map<UUID, Long> loadReviewCounts(Collection<UUID> userIds) {
        List<UUID> distinct = userIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return reviewRepository.countGroupedByUserId(distinct).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    /** Area labels for "Nearby", batched per page — Area#city is LAZY, so this must JOIN FETCH (see AreaRepository). */
    private Map<UUID, CommunityAreaSummary> loadAreas(List<UUID> areaIds) {
        List<UUID> distinct = areaIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return areaRepository.findAllByIdWithCity(distinct).stream()
                .collect(Collectors.toMap(Area::getId,
                        a -> new CommunityAreaSummary(a.getId(), a.getName(), a.getCity().getName())));
    }
}
