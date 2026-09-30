package com.bdreview.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PromotionRestrictionRepository extends JpaRepository<PromotionRestriction, UUID> {

    @Query("""
            SELECT r FROM PromotionRestriction r
            WHERE r.liftedAt IS NULL AND (r.endsAt IS NULL OR r.endsAt > :now)
            ORDER BY r.createdAt DESC
            """)
    List<PromotionRestriction> findActive(@Param("now") Instant now);

    @Query("""
            SELECT count(r) > 0 FROM PromotionRestriction r
            WHERE r.businessId = :businessId AND r.liftedAt IS NULL AND (r.endsAt IS NULL OR r.endsAt > :now)
            """)
    boolean isRestricted(@Param("businessId") UUID businessId, @Param("now") Instant now);
}
