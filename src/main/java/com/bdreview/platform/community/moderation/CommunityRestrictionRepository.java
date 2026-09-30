package com.bdreview.platform.community.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommunityRestrictionRepository extends JpaRepository<CommunityRestriction, UUID> {

    List<CommunityRestriction> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("""
            SELECT r FROM CommunityRestriction r
            WHERE r.userId = :userId AND r.status = com.bdreview.platform.community.moderation.CommunityRestriction.Status.ACTIVE
              AND r.startsAt <= :now AND (r.endsAt IS NULL OR r.endsAt > :now)
            ORDER BY r.createdAt DESC
            """)
    List<CommunityRestriction> findInEffect(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query("""
            SELECT r FROM CommunityRestriction r
            WHERE r.userId IN :userIds AND r.status = com.bdreview.platform.community.moderation.CommunityRestriction.Status.ACTIVE
              AND r.startsAt <= :now AND (r.endsAt IS NULL OR r.endsAt > :now)
            """)
    List<CommunityRestriction> findInEffectForUsers(@Param("userIds") Collection<UUID> userIds, @Param("now") Instant now);

    @Query("""
            SELECT r FROM CommunityRestriction r
            WHERE r.status = com.bdreview.platform.community.moderation.CommunityRestriction.Status.ACTIVE
              AND r.startsAt <= :now AND (r.endsAt IS NULL OR r.endsAt > :now)
              AND r.type <> com.bdreview.platform.community.moderation.CommunityRestriction.Type.WARN
            ORDER BY r.createdAt DESC
            """)
    Page<CommunityRestriction> findAllBlockingInEffect(@Param("now") Instant now, Pageable pageable);

    /** Scheduled expiry — see CommunityRestrictionExpiryJob. */
    @Query("""
            SELECT r FROM CommunityRestriction r
            WHERE r.status = com.bdreview.platform.community.moderation.CommunityRestriction.Status.ACTIVE
              AND r.endsAt IS NOT NULL AND r.endsAt <= :now
            """)
    List<CommunityRestriction> findExpired(@Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE CommunityRestriction r SET r.status = com.bdreview.platform.community.moderation.CommunityRestriction.Status.EXPIRED
            WHERE r.id IN :ids AND r.status = com.bdreview.platform.community.moderation.CommunityRestriction.Status.ACTIVE
            """)
    int markExpired(@Param("ids") Collection<UUID> ids);

    long countByUserIdAndTypeNot(UUID userId, CommunityRestriction.Type type);
}
