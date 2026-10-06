package com.bdreview.platform.listing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessPendingChangeRepository extends JpaRepository<BusinessPendingChange, UUID> {

    Page<BusinessPendingChange> findByStatusOrderByCreatedAtAsc(BusinessPendingChange.Status status, Pageable pageable);

    List<BusinessPendingChange> findByBusinessIdAndStatus(UUID businessId, BusinessPendingChange.Status status);

    Optional<BusinessPendingChange> findFirstByBusinessIdAndStatusOrderByCreatedAtDesc(UUID businessId, BusinessPendingChange.Status status);

    long countByStatus(BusinessPendingChange.Status status);
}
