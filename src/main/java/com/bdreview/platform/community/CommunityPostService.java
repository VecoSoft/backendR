package com.bdreview.platform.community;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * "Join Community" feature — a Facebook-style feed inside Jachai: members
 * post text and/or a single image (never video), react, comment, and can
 * tag/mention business listings in a post. Image bytes never touch this
 * service directly — same pre-signed-URL flow as gallery.BusinessPhotoService
 * (request an upload URL, upload straight to storage, then create the post
 * with the resulting cdnUrl).
 */
@Service
public class CommunityPostService {

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp", "gif");

    private final CommunityPostRepository postRepository;
    private final CommunityPostReactionRepository reactionRepository;
    private final CommunityPostCommentRepository commentRepository;
    private final CommunityPostMentionRepository mentionRepository;
    private final CommunityBusinessMentionRepository businessMentionRepository;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final ObjectStorageClient objectStorageClient;
    private final CommunityNotifier communityNotifier;

    public CommunityPostService(CommunityPostRepository postRepository,
                                 CommunityPostReactionRepository reactionRepository,
                                 CommunityPostCommentRepository commentRepository,
                                 CommunityPostMentionRepository mentionRepository,
                                 CommunityBusinessMentionRepository businessMentionRepository,
                                 UserRepository userRepository,
                                 BusinessRepository businessRepository,
                                 ObjectStorageClient objectStorageClient,
                                 CommunityNotifier communityNotifier) {
        this.postRepository = postRepository;
        this.reactionRepository = reactionRepository;
        this.commentRepository = commentRepository;
        this.mentionRepository = mentionRepository;
        this.businessMentionRepository = businessMentionRepository;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.objectStorageClient = objectStorageClient;
        this.communityNotifier = communityNotifier;
    }

    // -----------------------------------------------------------------
    // Image upload (pic only, no video)
    // -----------------------------------------------------------------

    public PreSignedUploadResponse requestImageUploadUrl(UUID userId, String filename) {
        String extension = extensionOf(filename);
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Only image files are allowed (jpg, jpeg, png, webp, gif)");
        }
        String key = objectStorageClient.buildObjectKey("community-post", userId.toString(), filename);
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
        boolean hasContent = request.content() != null && !request.content().isBlank();
        boolean hasImage = request.imageUrl() != null && !request.imageUrl().isBlank();
        if (!hasContent && !hasImage) {
            throw new BadRequestException("A post needs text or a picture");
        }

        CommunityPost post = postRepository.save(CommunityPost.builder()
                .authorUserId(userId)
                .content(hasContent ? request.content() : null)
                .imageUrl(hasImage ? request.imageUrl() : null)
                .build());

        saveMentions(post.getId(), request.mentionedBusinessIds());
        notifyMentionedBusinesses(post, request.mentionedBusinessIds());
        return toResponse(post, userId);
    }

    public CommunityPostResponse getPost(UUID postId, UUID viewerUserId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        return toResponse(post, viewerUserId);
    }

    public com.bdreview.platform.common.PageResponse<CommunityPostResponse> feed(int page, int size, UUID viewerUserId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<CommunityPost> posts = postRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc(pageable);
        return buildPageResponse(posts, viewerUserId);
    }

    public com.bdreview.platform.common.PageResponse<CommunityPostResponse> postsByAuthor(UUID authorUserId, int page, int size, UUID viewerUserId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<CommunityPost> posts = postRepository.findAllByAuthorUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(authorUserId, pageable);
        return buildPageResponse(posts, viewerUserId);
    }

    public com.bdreview.platform.common.PageResponse<CommunityPostResponse> postsMentioningBusiness(UUID businessId, int page, int size, UUID viewerUserId) {
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
        boolean hasContent = request.content() != null && !request.content().isBlank();
        boolean hasImage = post.getImageUrl() != null;
        if (!hasContent && !hasImage) {
            throw new BadRequestException("A post needs text or a picture");
        }
        post.setContent(hasContent ? request.content() : null);
        postRepository.save(post);

        mentionRepository.deleteByPostId(postId);
        saveMentions(postId, request.mentionedBusinessIds());
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

    private void saveMentions(UUID postId, List<UUID> mentionedBusinessIds) {
        if (mentionedBusinessIds == null || mentionedBusinessIds.isEmpty()) {
            return;
        }
        List<UUID> distinctIds = mentionedBusinessIds.stream().distinct().toList();
        List<Business> found = businessRepository.findAllById(distinctIds);
        if (found.size() != distinctIds.size() || found.stream().anyMatch(Business::isDeleted)) {
            throw new BadRequestException("One or more mentioned businesses could not be found");
        }
        List<CommunityPostMention> mentions = distinctIds.stream()
                .map(businessId -> CommunityPostMention.builder().postId(postId).businessId(businessId).build())
                .toList();
        mentionRepository.saveAll(mentions);
    }

    /** Fired once, on creation only — editing a post's mentions later does not re-notify. */
    private void notifyMentionedBusinesses(CommunityPost post, List<UUID> mentionedBusinessIds) {
        if (mentionedBusinessIds == null || mentionedBusinessIds.isEmpty()) {
            return;
        }
        for (Business business : businessRepository.findAllById(mentionedBusinessIds.stream().distinct().toList())) {
            if (business.getOwnerUserId().equals(post.getAuthorUserId())) {
                continue; // don't notify a business owner for mentioning their own listing
            }
            communityNotifier.postMentioned(business.getOwnerUserId(), post.getId(), business.getName());
        }
    }

    // -----------------------------------------------------------------
    // Reactions — Facebook-style single choice per user, toggled/swapped
    // -----------------------------------------------------------------

    @Transactional
    public void react(UUID userId, UUID postId, CommunityPostReactionType type) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));

        Optional<CommunityPostReaction> existing = reactionRepository.findByPostIdAndUserId(postId, userId);
        if (existing.isPresent()) {
            CommunityPostReaction current = existing.get();
            if (current.getReactionType() == type) {
                // same reaction tapped again -> remove it
                reactionRepository.deleteByPostIdAndUserId(postId, userId);
                adjustCount(postId, type, -1);
                return;
            }
            adjustCount(postId, current.getReactionType(), -1);
            current.setReactionType(type);
            reactionRepository.save(current);
            adjustCount(postId, type, 1);
            if (!post.getAuthorUserId().equals(userId)) {
                communityNotifier.newReaction(post.getAuthorUserId(), post.getId());
            }
            return;
        }

        reactionRepository.save(CommunityPostReaction.builder()
                .postId(postId).userId(userId).reactionType(type).build());
        adjustCount(postId, type, 1);
        if (!post.getAuthorUserId().equals(userId)) {
            communityNotifier.newReaction(post.getAuthorUserId(), post.getId());
        }
    }

    private void adjustCount(UUID postId, CommunityPostReactionType type, int delta) {
        switch (type) {
            case LIKE -> postRepository.adjustLikeCount(postId, delta);
            case LOVE -> postRepository.adjustLoveCount(postId, delta);
            case HAHA -> postRepository.adjustHahaCount(postId, delta);
            case WOW -> postRepository.adjustWowCount(postId, delta);
            case SAD -> postRepository.adjustSadCount(postId, delta);
            case ANGRY -> postRepository.adjustAngryCount(postId, delta);
        }
    }

    // -----------------------------------------------------------------
    // Comments
    // -----------------------------------------------------------------

    @Transactional
    public CommunityCommentResponse addComment(UUID userId, UUID postId, CreateCommunityCommentRequest request) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        CommunityPostComment comment = commentRepository.save(CommunityPostComment.builder()
                .postId(postId).authorUserId(userId).content(request.content()).build());
        postRepository.adjustCommentCount(postId, 1);
        if (!post.getAuthorUserId().equals(userId)) {
            communityNotifier.newComment(post.getAuthorUserId(), post.getId());
        }
        return toCommentResponse(comment);
    }

    public com.bdreview.platform.common.PageResponse<CommunityCommentResponse> listComments(UUID postId, int page, int size) {
        postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size), Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<CommunityPostComment> comments = commentRepository.findByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(postId, pageable);

        Map<UUID, User> authors = loadAuthors(comments.getContent().stream().map(CommunityPostComment::getAuthorUserId).toList());
        Page<CommunityCommentResponse> mapped = comments.map(c -> toCommentResponse(c, authors.get(c.getAuthorUserId())));
        return com.bdreview.platform.common.PageResponse.of(mapped);
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
    }

    // -----------------------------------------------------------------
    // @mention typeahead
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
    // Response assembly
    // -----------------------------------------------------------------

    private com.bdreview.platform.common.PageResponse<CommunityPostResponse> buildPageResponse(Page<CommunityPost> posts, UUID viewerUserId) {
        List<CommunityPost> content = posts.getContent();
        Map<UUID, User> authors = loadAuthors(content.stream().map(CommunityPost::getAuthorUserId).toList());
        Map<UUID, List<CommunityPostMention>> mentionsByPost = mentionRepository
                .findByPostIdIn(content.stream().map(CommunityPost::getId).toList())
                .stream().collect(Collectors.groupingBy(CommunityPostMention::getPostId));
        Map<UUID, Business> businessesById = loadBusinesses(mentionsByPost.values().stream()
                .flatMap(List::stream).map(CommunityPostMention::getBusinessId).distinct().toList());
        Map<UUID, CommunityPostReactionType> myReactions = viewerUserId == null ? Map.of() :
                reactionRepository.findByPostIdInAndUserId(content.stream().map(CommunityPost::getId).toList(), viewerUserId)
                        .stream().collect(Collectors.toMap(CommunityPostReaction::getPostId, CommunityPostReaction::getReactionType));

        Page<CommunityPostResponse> mapped = posts.map(post -> assembleResponse(
                post, authors.get(post.getAuthorUserId()),
                mentionsByPost.getOrDefault(post.getId(), List.of()), businessesById,
                myReactions.get(post.getId())));
        return com.bdreview.platform.common.PageResponse.of(mapped);
    }

    private CommunityPostResponse toResponse(CommunityPost post, UUID viewerUserId) {
        User author = userRepository.findById(post.getAuthorUserId()).orElse(null);
        List<CommunityPostMention> mentions = mentionRepository.findByPostId(post.getId());
        Map<UUID, Business> businessesById = loadBusinesses(mentions.stream().map(CommunityPostMention::getBusinessId).toList());
        CommunityPostReactionType myReaction = viewerUserId == null ? null :
                reactionRepository.findByPostIdAndUserId(post.getId(), viewerUserId)
                        .map(CommunityPostReaction::getReactionType).orElse(null);
        return assembleResponse(post, author, mentions, businessesById, myReaction);
    }

    private CommunityPostResponse assembleResponse(CommunityPost post, User author,
                                                     List<CommunityPostMention> mentions,
                                                     Map<UUID, Business> businessesById,
                                                     CommunityPostReactionType myReaction) {
        List<CommunityMentionedBusinessSummary> mentionSummaries = mentions.stream()
                .map(m -> businessesById.get(m.getBusinessId()))
                .filter(Objects::nonNull)
                .map(this::toBusinessSummary)
                .toList();

        return new CommunityPostResponse(
                post.getId(),
                toAuthorSummary(author, post.getAuthorUserId()),
                post.getContent(),
                post.getImageUrl(),
                post.getLikeCount(), post.getLoveCount(), post.getHahaCount(),
                post.getWowCount(), post.getSadCount(), post.getAngryCount(),
                post.getTotalReactionCount(),
                myReaction,
                post.getCommentCount(),
                mentionSummaries,
                post.getCreatedAt(), post.getUpdatedAt());
    }

    private CommunityCommentResponse toCommentResponse(CommunityPostComment comment) {
        User author = userRepository.findById(comment.getAuthorUserId()).orElse(null);
        return toCommentResponse(comment, author);
    }

    private CommunityCommentResponse toCommentResponse(CommunityPostComment comment, User author) {
        return new CommunityCommentResponse(
                comment.getId(),
                toAuthorSummary(author, comment.getAuthorUserId()),
                comment.getContent(),
                comment.getCreatedAt(), comment.getUpdatedAt());
    }

    private CommunityAuthorSummary toAuthorSummary(User user, UUID fallbackId) {
        if (user == null) {
            return new CommunityAuthorSummary(fallbackId, "Deleted user", null);
        }
        return new CommunityAuthorSummary(user.getId(), user.getName(), user.getProfilePhotoUrl());
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
}
