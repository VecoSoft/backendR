package com.bdreview.platform.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, UUID> {

    Optional<CommunityPost> findByIdAndDeletedAtIsNull(UUID id);

    Page<CommunityPost> findAllByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    Page<CommunityPost> findAllByAuthorUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID authorUserId, Pageable pageable);

    /** Feed filtered to posts mentioning a given business (business profile's "community mentions" tab). */
    @Query("""
            SELECT p FROM CommunityPost p
            WHERE p.deletedAt IS NULL
              AND p.id IN (SELECT m.postId FROM CommunityPostMention m WHERE m.businessId = :businessId)
            ORDER BY p.createdAt DESC
            """)
    Page<CommunityPost> findAllMentioningBusiness(@Param("businessId") UUID businessId, Pageable pageable);

    // -----------------------------------------------------------------
    // Atomic counter updates — mirrors BusinessRepository#adjustLikeCount /
    // Review's vote counters: never read-modify-write against the
    // optimistic-lock `version` column.
    // -----------------------------------------------------------------
    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.likeCount = p.likeCount + :delta WHERE p.id = :id")
    void adjustLikeCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.loveCount = p.loveCount + :delta WHERE p.id = :id")
    void adjustLoveCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.hahaCount = p.hahaCount + :delta WHERE p.id = :id")
    void adjustHahaCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.wowCount = p.wowCount + :delta WHERE p.id = :id")
    void adjustWowCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.sadCount = p.sadCount + :delta WHERE p.id = :id")
    void adjustSadCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.angryCount = p.angryCount + :delta WHERE p.id = :id")
    void adjustAngryCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.commentCount = p.commentCount + :delta WHERE p.id = :id")
    void adjustCommentCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("UPDATE CommunityPost p SET p.deletedAt = :now WHERE p.id = :id AND p.deletedAt IS NULL")
    int softDelete(@Param("id") UUID id, @Param("now") Instant now);
}
