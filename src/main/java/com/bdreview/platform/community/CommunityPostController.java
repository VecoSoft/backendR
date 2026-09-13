package com.bdreview.platform.community;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.gallery.PreSignedUploadResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * "Join Community" — Facebook-style feed (spec: nav bar entry point, text +
 * single image post, reactions, comments, business mentions). The feed and
 * comment listing are public reads (see auth.SecurityConfig's permitAll for
 * GET /api/v1/community/**), same as the business browse/search surface;
 * everything else requires a logged-in user via CurrentUser.
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.feed(page, size, currentUserIdOrNull()));
    }

    @GetMapping("/posts/{postId}")
    public ResponseEntity<CommunityPostResponse> getPost(@PathVariable UUID postId) {
        return ResponseEntity.ok(communityPostService.getPost(postId, currentUserIdOrNull()));
    }

    @GetMapping("/users/{userId}/posts")
    public ResponseEntity<PageResponse<CommunityPostResponse>> postsByAuthor(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.postsByAuthor(userId, page, size, currentUserIdOrNull()));
    }

    /** Powers a "Community mentions" tab on a business's own profile page. */
    @GetMapping("/businesses/{businessId}/posts")
    public ResponseEntity<PageResponse<CommunityPostResponse>> postsMentioningBusiness(
            @PathVariable UUID businessId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.postsMentioningBusiness(businessId, page, size, currentUserIdOrNull()));
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

    @PostMapping("/posts/{postId}/reactions")
    public ResponseEntity<Void> react(@PathVariable UUID postId, @Valid @RequestBody CommunityPostReactionRequest request) {
        communityPostService.react(CurrentUser.id(), postId, request.reactionType());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<PageResponse<CommunityCommentResponse>> listComments(
            @PathVariable UUID postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(communityPostService.listComments(postId, page, size));
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

    /** @-mention typeahead while composing a post. */
    @GetMapping("/mentions/search")
    public ResponseEntity<List<CommunityMentionedBusinessSummary>> searchMentionCandidates(@RequestParam String q) {
        return ResponseEntity.ok(communityPostService.searchMentionCandidates(q));
    }

    /**
     * Same id/anonymous check as common.CurrentUser, but returns null instead
     * of throwing — needed because the feed/comments/single-post reads are
     * public and must still work for a logged-out visitor.
     */
    private UUID currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
