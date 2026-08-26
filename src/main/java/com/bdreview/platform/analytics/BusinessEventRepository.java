package com.bdreview.platform.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BusinessEventRepository extends JpaRepository<BusinessEvent, UUID> {

    /** PROFILE_VIEW dedupe: has this session already been counted for this business recently? */
    boolean existsByBusinessIdAndSessionIdAndEventTypeAndCreatedAtAfter(
            UUID businessId, String sessionId, BusinessEventType eventType, Instant after);

    /**
     * Dashboard aggregation — COUNT grouped by type, never row-by-row in Java.
     * Each row is [event_type (String), count (Long)].
     */
    @Query(value = """
            SELECT event_type, COUNT(*)
            FROM business_event
            WHERE business_id = :businessId AND created_at >= :from
            GROUP BY event_type
            """, nativeQuery = true)
    List<Object[]> aggregateSince(@Param("businessId") UUID businessId, @Param("from") Instant from);

    @Query(value = """
            SELECT event_type, COUNT(*)
            FROM business_event
            WHERE business_id = :businessId
            GROUP BY event_type
            """, nativeQuery = true)
    List<Object[]> aggregateAllTime(@Param("businessId") UUID businessId);
}
