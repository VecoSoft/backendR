package com.bdreview.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

    List<MenuItem> findByBusinessIdOrderBySortOrderAsc(UUID businessId);

    long countByBusinessId(UUID businessId);

    boolean existsByBusinessId(UUID businessId);

    void deleteByIdAndBusinessId(UUID id, UUID businessId);
}
