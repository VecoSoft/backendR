package com.bdreview.platform.business;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BusinessHoursExceptionRepository extends JpaRepository<BusinessHoursException, UUID> {

    List<BusinessHoursException> findByBusinessIdOrderByStartDateAsc(UUID businessId);

    /** Batched lookup for a list of businesses (owner workspace's "my businesses" list) — avoids N+1. */
    List<BusinessHoursException> findByBusinessIdIn(Collection<UUID> businessIds);

    void deleteByIdAndBusinessId(UUID id, UUID businessId);
}
