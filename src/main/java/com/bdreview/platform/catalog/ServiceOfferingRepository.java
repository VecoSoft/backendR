package com.bdreview.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, UUID> {

    List<ServiceOffering> findByBusinessIdAndSectionOrderBySortOrderAsc(UUID businessId, ServiceSection section);

    long countByBusinessIdAndSection(UUID businessId, ServiceSection section);

    boolean existsByBusinessIdAndSection(UUID businessId, ServiceSection section);

    void deleteByIdAndBusinessId(UUID id, UUID businessId);
}
