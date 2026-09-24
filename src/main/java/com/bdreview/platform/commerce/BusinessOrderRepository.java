package com.bdreview.platform.commerce;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BusinessOrderRepository extends JpaRepository<BusinessOrder, UUID> {

    Page<BusinessOrder> findByCustomerUserIdOrderByCreatedAtDesc(UUID customerUserId, Pageable pageable);

    Page<BusinessOrder> findByBusinessIdOrderByCreatedAtDesc(UUID businessId, Pageable pageable);

    Page<BusinessOrder> findByBusinessIdAndStatusOrderByCreatedAtDesc(UUID businessId, OrderStatus status, Pageable pageable);

    long countByBusinessIdAndStatus(UUID businessId, OrderStatus status);

    /** Orders the business never actioned — the pool the auto-cancel job sweeps. */
    List<BusinessOrder> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    /** Next order number from the DB sequence — used to build "J-1045". */
    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
