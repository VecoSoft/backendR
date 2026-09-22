package com.bdreview.platform.business;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BusinessOperatingHoursRepository extends JpaRepository<BusinessOperatingHours, UUID> {

    List<BusinessOperatingHours> findByBusinessId(UUID businessId);

    /** Batched lookup for a list of businesses (e.g. the owner workspace's "my businesses" list) — avoids N+1. */
    List<BusinessOperatingHours> findByBusinessIdIn(Collection<UUID> businessIds);

    /**
     * A bulk {@code @Modifying} query, not a derived delete — same reasoning as
     * StaffWeeklyScheduleRepository#deleteByTeamMemberId: a derived delete queues
     * removal via the persistence context, and Hibernate's default flush ordering
     * runs inserts BEFORE deletes, which would spuriously violate the
     * (business_id, day_of_week) unique constraint on a replace. A bulk query
     * executes immediately instead.
     */
    @Modifying
    @Query("delete from BusinessOperatingHours h where h.businessId = :businessId")
    void deleteByBusinessId(@Param("businessId") UUID businessId);
}
