package com.bdreview.platform.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, UUID>, JpaSpecificationExecutor<CommunityPost> {

    Optional<CommunityPost> findByIdAndDeletedAtIsNull(UUID id);

    // -----------------------------------------------------------------
    // Feed variants — kept as distinct finder methods (rather than one
    // mega-query with `:param IS NULL OR ...` branches) so CommunityPostService
    // picks the right one per (tab, topic, sort); avoids binding a possibly-
    // null collection to an IN clause, which Hibernate handles poorly.
    //
    // "Live" (V56) = not author-deleted, moderation status ACTIVE, and inside
    // its scheduled window (only announcements set visibleFrom/visibleUntil).
    // Pinned posts lead: GLOBAL pins everywhere, TOPIC pins when that topic
    // is the active filter, AREA pins in that area's Nearby feed. An official
    // announcement targeted at one topic/area only shows up there.
    // -----------------------------------------------------------------

    /**
     * Plain/topic/postType feed, New or Top sort. topic and postType are both optional scalar
     * filters (unlike the Following-tab author-id list below, a nullable scalar bound with
     * `:param IS NULL OR ...` is not the Hibernate IN-clause pitfall the class comment above
     * warns about — that only applies to a nullable *collection*).
     */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND p.status = com.bdreview.platform.community.CommunityContentStatus.ACTIVE
              AND (p.visibleFrom IS NULL OR p.visibleFrom <= :now)
              AND (p.visibleUntil IS NULL OR p.visibleUntil > :now)
              AND (:topic IS NULL OR p.topic = :topic)
              AND (:postType IS NULL OR p.postType = :postType)
              AND (p.official = false OR p.pinScope IS NULL OR p.pinScope = 'GLOBAL'
                   OR (p.pinScope = 'TOPIC' AND p.topic = :topic))
            ORDER BY CASE WHEN p.pinned = true AND (p.pinnedUntil IS NULL OR p.pinnedUntil > :now)
                               AND (p.pinScope = 'GLOBAL' OR (p.pinScope = 'TOPIC' AND p.topic = :topic))
                          THEN 0 ELSE 1 END,
                     p.createdAt DESC
            """)
    Page<CommunityPost> findLiveByFiltersOrderByCreatedAtDesc(
            @Param("topic") String topic, @Param("postType") CommunityPostType postType,
            @Param("now") Instant now, Pageable pageable);

    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND p.status = com.bdreview.platform.community.CommunityContentStatus.ACTIVE
              AND (p.visibleFrom IS NULL OR p.visibleFrom <= :now)
              AND (p.visibleUntil IS NULL OR p.visibleUntil > :now)
              AND (:topic IS NULL OR p.topic = :topic)
              AND (:postType IS NULL OR p.postType = :postType)
              AND (p.official = false OR p.pinScope IS NULL OR p.pinScope = 'GLOBAL'
                   OR (p.pinScope = 'TOPIC' AND p.topic = :topic))
            ORDER BY CASE WHEN p.pinned = true AND (p.pinnedUntil IS NULL OR p.pinnedUntil > :now)
                               AND (p.pinScope = 'GLOBAL' OR (p.pinScope = 'TOPIC' AND p.topic = :topic))
                          THEN 0 ELSE 1 END,
                     (p.upvoteCount - p.downvoteCount) DESC, p.createdAt DESC
            """)
    Page<CommunityPost> findLiveByFiltersOrderByScoreDesc(
            @Param("topic") String topic, @Param("postType") CommunityPostType postType,
            @Param("now") Instant now, Pageable pageable);

    /** "Nearby" tab — NEW-sort only in V1; area pins (incl. area-targeted announcements) lead. */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND p.status = com.bdreview.platform.community.CommunityContentStatus.ACTIVE
              AND (p.visibleFrom IS NULL OR p.visibleFrom <= :now)
              AND (p.visibleUntil IS NULL OR p.visibleUntil > :now)
              AND p.areaId = :areaId
            ORDER BY CASE WHEN p.pinned = true AND (p.pinnedUntil IS NULL OR p.pinnedUntil > :now)
                               AND p.pinScope IN ('GLOBAL', 'AREA')
                          THEN 0 ELSE 1 END,
                     p.createdAt DESC
            """)
    Page<CommunityPost> findLiveByArea(@Param("areaId") UUID areaId, @Param("now") Instant now, Pageable pageable);

    /** "Following" tab — NEW-sort only in V1, same reasoning as Nearby. */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND p.status = com.bdreview.platform.community.CommunityContentStatus.ACTIVE
              AND (p.visibleFrom IS NULL OR p.visibleFrom <= :now)
              AND (p.visibleUntil IS NULL OR p.visibleUntil > :now)
              AND p.authorUserId IN :authorUserIds
              AND p.authorBusinessId IS NULL
            ORDER BY p.createdAt DESC
            """)
    Page<CommunityPost> findLiveByAuthors(@Param("authorUserIds") Collection<UUID> authorUserIds,
                                          @Param("now") Instant now, Pageable pageable);

    /** V58: a member profile's own posts — never the posts that member published as a business. */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.authorUserId = :authorUserId AND p.deletedAt IS NULL AND p.authorBusinessId IS NULL
            ORDER BY p.createdAt DESC
            """)
    Page<CommunityPost> findMemberPostsByAuthor(@Param("authorUserId") UUID authorUserId, Pageable pageable);

    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.authorUserId = :authorUserId AND p.deletedAt IS NULL AND p.authorBusinessId IS NULL
              AND p.status = :status
            ORDER BY p.createdAt DESC
            """)
    Page<CommunityPost> findMemberPostsByAuthorAndStatus(@Param("authorUserId") UUID authorUserId,
                                                        @Param("status") CommunityContentStatus status, Pageable pageable);

    /** A profile owner viewing their own profile — every non-deleted post, whatever its moderation state. */
    Page<CommunityPost> findAllByAuthorUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID authorUserId, Pageable pageable);

    Page<CommunityPost> findAllByAuthorUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID authorUserId, CommunityContentStatus status, Pageable pageable);

    long countByAuthorUserIdAndStatusAndDeletedAtIsNull(UUID authorUserId, CommunityContentStatus status);

    /** New-user approval rule — how many of this author's posts have ever gone live (see CommunityPolicyService#checkPost). */
    long countByAuthorUserIdAndStatus(UUID authorUserId, CommunityContentStatus status);

    /** Rate limiting — see CommunityPolicyService (posts per day). */
    long countByAuthorUserIdAndCreatedAtAfter(UUID authorUserId, Instant since);

    /** Feed filtered to posts mentioning a given business (business profile's "community mentions" tab). */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND p.status = com.bdreview.platform.community.CommunityContentStatus.ACTIVE
              AND p.id IN (SELECT m.postId FROM CommunityPostMention m WHERE m.businessId = :businessId)
            ORDER BY p.createdAt DESC
            """)
    Page<CommunityPost> findAllMentioningBusiness(@Param("businessId") UUID businessId, Pageable pageable);

    // -----------------------------------------------------------------
    // "Questions for you" widget — see CommunityPostService#recommendedQuestions.
    // -----------------------------------------------------------------

    /** Topic affinity signal: which topics has this user actually posted in. Empty for a brand-new user. */
    @Query("SELECT DISTINCT p.topic FROM CommunityPost p WHERE p.authorUserId = :userId AND p.deletedAt IS NULL")
    List<String> findDistinctTopicsByAuthor(@Param("userId") UUID userId);

    /**
     * Open questions this viewer hasn't asked, already followed/passed, or already answered —
     * ranked by topic affinity first (topics they themselves post in), then most recent.
     * `affinityTopics` is never empty at the call site (falls back to every topic, which makes
     * the CASE a no-op and the ordering pure recency) since JPQL's IN rejects an empty collection.
     */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND p.status = com.bdreview.platform.community.CommunityContentStatus.ACTIVE
              AND p.postType = :postType
              AND p.closedAt IS NULL
              AND p.authorUserId <> :viewerId
              AND p.id NOT IN (SELECT f.postId FROM CommunityQuestionFollow f WHERE f.userId = :viewerId)
              AND p.id NOT IN (SELECT ps.postId FROM CommunityQuestionPass ps WHERE ps.userId = :viewerId)
              AND p.id NOT IN (SELECT c.postId FROM CommunityPostComment c WHERE c.authorUserId = :viewerId AND c.deletedAt IS NULL)
            ORDER BY CASE WHEN p.topic IN :affinityTopics THEN 0 ELSE 1 END, p.createdAt DESC
            """)
    List<CommunityPost> findRecommendedQuestions(
            @Param("postType") CommunityPostType postType,
            @Param("viewerId") UUID viewerId,
            @Param("affinityTopics") Collection<String> affinityTopics,
            Pageable pageable);

    // -----------------------------------------------------------------
    // Moderation (V56)
    // -----------------------------------------------------------------

    List<CommunityPost> findAllByAuthorUserIdAndDeletedAtIsNull(UUID authorUserId);

    long countByStatusAndDeletedAtIsNull(CommunityContentStatus status);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.reportCount = p.reportCount + 1 WHERE p.id = :id")
    void incrementReportCount(@Param("id") UUID id);

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
