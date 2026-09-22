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
import java.util.Optional;
import java.util.UUID;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, UUID> {

    Optional<CommunityPost> findByIdAndDeletedAtIsNull(UUID id);

    // -----------------------------------------------------------------
    // Feed variants — kept as distinct finder methods (rather than one
    // mega-query with `:param IS NULL OR ...` branches) so CommunityPostService
    // picks the right one per (tab, topic, sort); avoids binding a possibly-
    // null collection to an IN clause, which Hibernate handles poorly.
    // -----------------------------------------------------------------

    /**
     * Plain/topic/postType feed, New or Top sort. topic and postType are both optional scalar
     * filters (unlike the Following-tab author-id list below, a nullable enum bound with
     * `:param IS NULL OR ...` is not the Hibernate IN-clause pitfall the class comment above
     * warns about — that only applies to a nullable *collection*) — one unified method per
     * sort order rather than a 4-way (topic present/absent x postType present/absent) matrix.
     */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND (:topic IS NULL OR p.topic = :topic)
              AND (:postType IS NULL OR p.postType = :postType)
            ORDER BY p.createdAt DESC
            """)
    Page<CommunityPost> findAllByFiltersOrderByCreatedAtDesc(
            @Param("topic") CommunityTopic topic, @Param("postType") CommunityPostType postType, Pageable pageable);

    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND (:topic IS NULL OR p.topic = :topic)
              AND (:postType IS NULL OR p.postType = :postType)
            ORDER BY (p.upvoteCount - p.downvoteCount) DESC, p.createdAt DESC
            """)
    Page<CommunityPost> findAllByFiltersOrderByScoreDesc(
            @Param("topic") CommunityTopic topic, @Param("postType") CommunityPostType postType, Pageable pageable);

    /** "Nearby" tab — NEW-sort only in V1 (score-sort x area is a rare-enough combo to skip for now). */
    Page<CommunityPost> findAllByAreaIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID areaId, Pageable pageable);

    /** "Following" tab — NEW-sort only in V1, same reasoning as Nearby. */
    Page<CommunityPost> findAllByAuthorUserIdInAndDeletedAtIsNullOrderByCreatedAtDesc(Collection<UUID> authorUserIds, Pageable pageable);

    Page<CommunityPost> findAllByAuthorUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID authorUserId, Pageable pageable);

    long countByAuthorUserIdAndDeletedAtIsNull(UUID authorUserId);

    /** Rate limiting — see CommunityPostService#createPost, same @Value-injected-threshold pattern as OtpService/ReportService. */
    long countByAuthorUserIdAndCreatedAtAfter(UUID authorUserId, Instant since);

    /** Feed filtered to posts mentioning a given business (business profile's "community mentions" tab). */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND p.id IN (SELECT m.postId FROM CommunityPostMention m WHERE m.businessId = :businessId)
            ORDER BY p.createdAt DESC
            """)
    Page<CommunityPost> findAllMentioningBusiness(@Param("businessId") UUID businessId, Pageable pageable);

    // -----------------------------------------------------------------
    // Atomic counter updates — never read-modify-write against the
    // optimistic-lock `version` column, same convention as before.
    // -----------------------------------------------------------------
    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.upvoteCount = p.upvoteCount + :delta WHERE p.id = :id")
    void adjustUpvoteCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.downvoteCount = p.downvoteCount + :delta WHERE p.id = :id")
    void adjustDownvoteCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.commentCount = p.commentCount + :delta WHERE p.id = :id")
    void adjustCommentCount(@Param("id") UUID id, @Param("delta") int delta);

    /** Top-level (depth 0) comments only — see CommunityPostService#addComment/deleteComment. */
    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.answerCount = p.answerCount + :delta WHERE p.id = :id")
    void adjustAnswerCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.deletedAt = :now WHERE p.id = :id AND p.deletedAt IS NULL")
    int softDelete(@Param("id") UUID id, @Param("now") Instant now);
}
