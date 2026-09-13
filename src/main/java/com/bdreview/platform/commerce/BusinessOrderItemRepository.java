package com.bdreview.platform.commerce;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BusinessOrderItemRepository extends JpaRepository<BusinessOrderItem, UUID> {

    List<BusinessOrderItem> findByOrderId(UUID orderId);

    List<BusinessOrderItem> findByOrderIdIn(List<UUID> orderIds);
}
