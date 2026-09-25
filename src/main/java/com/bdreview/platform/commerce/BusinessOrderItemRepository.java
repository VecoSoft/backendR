package com.bdreview.platform.commerce;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BusinessOrderItemRepository extends JpaRepository<BusinessOrderItem, UUID> {

    List<BusinessOrderItem> findByOrderId(UUID orderId);

    List<BusinessOrderItem> findByOrderIdIn(List<UUID> orderIds);

    /**
     * How many non-cancelled/rejected orders this customer has already used this offer in —
     * backs OrderService's enforcement of Offer#maxRedemptionsPerUser for the online-order path
     * (previously only checked in the separate in-person claim/redeem flow).
     */
    @Query("SELECT COUNT(i) FROM BusinessOrderItem i, BusinessOrder o " +
            "WHERE i.orderId = o.id AND i.offerId = :offerId AND o.customerUserId = :customerUserId " +
            "AND o.status NOT IN (com.bdreview.platform.commerce.OrderStatus.CANCELLED, com.bdreview.platform.commerce.OrderStatus.REJECTED)")
    long countByOfferIdAndCustomerUserId(@Param("offerId") UUID offerId, @Param("customerUserId") UUID customerUserId);
}
