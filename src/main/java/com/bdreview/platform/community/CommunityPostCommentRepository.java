package com.bdreview.platform.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityPostCommentRepository extends JpaRepository<CommunityPostComment, UUID> {

    Page<CommunityPostComment> findByPostIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID postId, Pageable pageable);

    /** Community profile page's "Comments" tab. */
    Page<CommunityPostComment> findByAuthorUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID authorUserId, Pageable pageable);

    Optional<CommunityPostComment> findByIdAndPostIdAndDeletedAtIsNull(UUID id, UUID postId);

    /** Report resolution needs a comment by id alone — the reporter doesn't carry its postId. */
    Optional<CommunityPostComment> findByIdAndDeletedAtIsNull(UUID id);

    long countByAuthorUserIdAndDeletedAtIsNull(UUID authorUserId);

    /** Rate limiting — see CommunityPostService#addComment. */
    long countByAuthorUserIdAndCreatedAtAfter(UUID authorUserId, Instant since);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPostComment c SET c.upvoteCount = c.upvoteCount + :delta WHERE c.id = :id")
    void adjustUpvoteCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPostComment c SET c.downvoteCount = c.downvoteCount + :delta WHERE c.id = :id")
    void adjustDownvoteCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPostComment c SET c.deletedAt = :now WHERE c.id = :id AND c.deletedAt IS NULL")
    int softDelete(@Param("id") UUID id, @Param("now") Instant now);

    // -----------------------------------------------------------------
    // Best Answer — see CommunityPostService#markBestAnswer/unmarkBestAnswer.
    // -----------------------------------------------------------------

    /**
     * Batched existence check for a page of posts (feed's questionStatus=ANSWERED derivation)
     * — mirrors CommunityPostService#loadPolls' batching. Note: JPQL paths use the entity's
     * FIELD name (`bestAnswer`), not the Lombok-generated `isBestAnswer()` getter name.
     */
    @Query("SELECT c.postId FROM CommunityPostComment c WHERE c.postId IN :postIds AND c.bestAnswer = true AND c.deletedAt IS NULL")
    List<UUID> findPostIdsWithBestAnswer(@Param("postIds") Collection<UUID> postIds);

    /** Single-post existence check — see CommunityPostService#toResponse. */
    @Query("SELECT c FROM CommunityPostComment c WHERE c.postId = :postId AND c.bestAnswer = true AND c.deletedAt IS NULL")
    Optional<CommunityPostComment> findBestAnswerByPostId(@Param("postId") UUID postId);

    /** Clear-then-set swap (same shape as the poll vote swap) — enforces "at most one per post" alongside the DB partial unique index. */
    @Modifying
    @Transactional
    @Query("UPDATE CommunityPostComment c SET c.bestAnswer = false WHERE c.postId = :postId AND c.bestAnswer = true")
    void clearBestAnswer(@Param("postId") UUID postId);
}
