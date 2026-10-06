package com.bdreview.platform.accountcontrol;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UserRestrictionRepository extends JpaRepository<UserRestriction, UUID> {

    List<UserRestriction> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Restrictions in force right now, BAN first, then the one that ends last. */
    @Query("""
            SELECT r FROM UserRestriction r
            WHERE r.userId = :userId AND r.liftedAt IS NULL AND r.startsAt <= :now
              AND (r.endsAt IS NULL OR r.endsAt > :now)
            ORDER BY CASE WHEN r.type = com.bdreview.platform.accountcontrol.UserRestriction.Type.BAN THEN 0 ELSE 1 END,
                     r.endsAt DESC
            """)
    List<UserRestriction> findInEffect(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Batch version for the admin user list. */
    @Query("""
            SELECT r FROM UserRestriction r
            WHERE r.userId IN :userIds AND r.liftedAt IS NULL AND r.startsAt <= :now
              AND (r.endsAt IS NULL OR r.endsAt > :now)
            """)
    List<UserRestriction> findInEffectForUsers(@Param("userIds") java.util.Collection<UUID> userIds, @Param("now") Instant now);
}
