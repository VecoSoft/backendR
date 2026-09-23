package com.bdreview.platform.community;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * "Join Community" V1 — Reddit-style pseudonymous discussion (spec: nav bar
 * entry point, title+body text post, topics, votes, threaded comments,
 * optional business tag). The feed and comment listing are public reads
 * (see auth.SecurityConfig's permitAll for GET /api/v1/community/**), same
 * as the business browse/search surface; everything else requires a
 * logged-in user via CurrentUser. See CommunityProfileController for the
 * Community-username setup/lookup endpoints.
 */
@RestController
@RequestMapping("/api/v1/community")
public class CommunityPostController {

    private final CommunityPostService communityPostService;

    public CommunityPostController(CommunityPostService communityPostService) {
        this.communityPostService = communityPostService;
    }

    @PostMapping("/posts/upload-url")
    public ResponseEntity<PreSignedUploadResponse> requestUploadUrl(@RequestParam String filename) {
        return ResponseEntity.ok(communityPostService.requestImageUploadUrl(CurrentUser.id(), filename));
    }

    @PostMapping("/posts")
    public ResponseEntity<CommunityPostResponse> create(@Valid @RequestBody CreateCommunityPostRequest request) {
        return ResponseEntity.ok(communityPostService.createPost(CurrentUser.id(), request));
    }

    @GetMapping("/posts")
    public ResponseEntity<PageResponse<CommunityPostResponse>> feed(
            @RequestParam(required = false) CommunityFeedTab tab,
            @RequestParam(required = false) CommunityTopic topic,
            @RequestParam(required = false) CommunityPostType postType,
            @RequestParam(required = false, defaultValue = "NEW") CommunitySortOrder sort,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.feed(tab, topic, postType, sort, areaId, page, size, CurrentUser.idOrNull()));
    }

    @GetMapping("/posts/{postId}")
    public ResponseEntity<CommunityPostResponse> getPost(@PathVariable UUID postId) {
        return ResponseEntity.ok(communityPostService.getPost(postId, CurrentUser.idOrNull()));
    }

    @GetMapping("/users/{userId}/posts")
    public ResponseEntity<PageResponse<CommunityPostResponse>> postsByAuthor(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.postsByAuthor(userId, page, size, CurrentUser.idOrNull()));
    }

    /** Community profile page's "Comments" tab. */
    @GetMapping("/users/{userId}/comments")
    public ResponseEntity<PageResponse<CommunityCommentResponse>> commentsByAuthor(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.commentsByAuthor(userId, page, size, CurrentUser.idOrNull()));
    }

    /** Powers a "Community mentions" tab on a business's own profile page. */
    @GetMapping("/businesses/{businessId}/posts")
    public ResponseEntity<PageResponse<CommunityPostResponse>> postsMentioningBusiness(
            @PathVariable UUID businessId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.postsMentioningBusiness(businessId, page, size, CurrentUser.idOrNull()));
    }

    @PatchMapping("/posts/{postId}")
    public ResponseEntity<CommunityPostResponse> update(@PathVariable UUID postId,
                                                          @Valid @RequestBody UpdateCommunityPostRequest request) {
        return ResponseEntity.ok(communityPostService.updatePost(CurrentUser.id(), postId, request));
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<Void> delete(@PathVariable UUID postId) {
        communityPostService.deletePost(CurrentUser.id(), postId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/votes")
    public ResponseEntity<Void> vote(@PathVariable UUID postId, @Valid @RequestBody CommunityPostVoteRequest request) {
        communityPostService.vote(CurrentUser.id(), postId, request.voteType());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/poll/votes")
    public ResponseEntity<CommunityPollResponse> votePoll(@PathVariable UUID postId,
                                                            @Valid @RequestBody CommunityPollVoteRequest request) {
        return ResponseEntity.ok(communityPostService.votePoll(CurrentUser.id(), postId, request.optionId()));
    }

    @PostMapping("/posts/{postId}/comments/{commentId}/votes")
    public ResponseEntity<Void> voteComment(@PathVariable UUID postId, @PathVariable UUID commentId,
                                             @Valid @RequestBody CommunityCommentVoteRequest request) {
        communityPostService.voteComment(CurrentUser.id(), commentId, request.voteType());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<PageResponse<CommunityCommentResponse>> listComments(
            @PathVariable UUID postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.listComments(postId, page, size, CurrentUser.idOrNull()));
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommunityCommentResponse> addComment(@PathVariable UUID postId,
                                                                 @Valid @RequestBody CreateCommunityCommentRequest request) {
        return ResponseEntity.ok(communityPostService.addComment(CurrentUser.id(), postId, request));
    }

    @DeleteMapping("/posts/{postId}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable UUID postId, @PathVariable UUID commentId) {
        communityPostService.deleteComment(CurrentUser.id(), postId, commentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/comments/{commentId}/best-answer")
    public ResponseEntity<CommunityCommentResponse> markBestAnswer(@PathVariable UUID postId, @PathVariable UUID commentId) {
        return ResponseEntity.ok(communityPostService.markBestAnswer(CurrentUser.id(), postId, commentId));
    }

    @DeleteMapping("/posts/{postId}/comments/{commentId}/best-answer")
    public ResponseEntity<CommunityCommentResponse> unmarkBestAnswer(@PathVariable UUID postId, @PathVariable UUID commentId) {
        return ResponseEntity.ok(communityPostService.unmarkBestAnswer(CurrentUser.id(), postId, commentId));
    }

    @PostMapping("/posts/{postId}/close")
    public ResponseEntity<Void> closeQuestion(@PathVariable UUID postId) {
        communityPostService.closeQuestion(CurrentUser.id(), postId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/reopen")
    public ResponseEntity<Void> reopenQuestion(@PathVariable UUID postId) {
        communityPostService.reopenQuestion(CurrentUser.id(), postId);
        return ResponseEntity.noContent().build();
    }

    /** Business picker while composing a post. */
    @GetMapping("/mentions/search")
    public ResponseEntity<List<CommunityMentionedBusinessSummary>> searchMentionCandidates(@RequestParam String q) {
        return ResponseEntity.ok(communityPostService.searchMentionCandidates(q));
    }

    /** "Questions for you" widget — personalized, so logged-in only. */
    @GetMapping("/questions/recommended")
    public ResponseEntity<List<CommunityQuestionRecommendationResponse>> recommendedQuestions() {
        return ResponseEntity.ok(communityPostService.recommendedQuestions(CurrentUser.id()));
    }

    @PostMapping("/posts/{postId}/follow-question")
    public ResponseEntity<Void> followQuestion(@PathVariable UUID postId) {
        communityPostService.followQuestion(CurrentUser.id(), postId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/posts/{postId}/follow-question")
    public ResponseEntity<Void> unfollowQuestion(@PathVariable UUID postId) {
        communityPostService.unfollowQuestion(CurrentUser.id(), postId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/pass")
    public ResponseEntity<Void> passQuestion(@PathVariable UUID postId) {
        communityPostService.passQuestion(CurrentUser.id(), postId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/follow")
    public ResponseEntity<Void> follow(@PathVariable UUID userId) {
        communityPostService.follow(CurrentUser.id(), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{userId}/follow")
    public ResponseEntity<Void> unfollow(@PathVariable UUID userId) {
        communityPostService.unfollow(CurrentUser.id(), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/following")
    public ResponseEntity<PageResponse<CommunityFollowListItem>> following(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.following(userId, page, size, CurrentUser.idOrNull()));
    }

    @GetMapping("/users/{userId}/followers")
    public ResponseEntity<PageResponse<CommunityFollowListItem>> followers(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.followers(userId, page, size, CurrentUser.idOrNull()));
    }
}
