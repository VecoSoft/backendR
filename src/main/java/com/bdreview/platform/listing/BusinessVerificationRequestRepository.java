package com.bdreview.platform.listing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BusinessVerificationRequestRepository extends JpaRepository<BusinessVerificationRequest, UUID> {

    Page<BusinessVerificationRequest> findByStatusOrderByCreatedAtAsc(BusinessVerificationRequest.Status status, Pageable pageable);

    List<BusinessVerificationRequest> findByBusinessIdOrderByCreatedAtDesc(UUID businessId);

    boolean existsByBusinessIdAndStatus(UUID businessId, BusinessVerificationRequest.Status status);
}
