package com.bdreview.platform.updates;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessUpdateRepository extends JpaRepository<BusinessUpdate, UUID> {

    /** Public list — published only, newest first. */
    Page<BusinessUpdate> findByBusinessIdAndPublishedTrueOrderByPublishedAtDesc(UUID businessId, Pageable pageable);

    /** Owner "manage" list — every update, newest first. */
    Page<BusinessUpdate> findByBusinessIdOrderByCreatedAtDesc(UUID businessId, Pageable pageable);

    boolean existsByBusinessIdAndPublishedTrue(UUID businessId);

    void deleteByIdAndBusinessId(UUID id, UUID businessId);
}
