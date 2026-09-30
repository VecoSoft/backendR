package com.bdreview.platform.commerce;

import com.bdreview.platform.commerce.CommerceRequests.PlaceOrderRequest;
import com.bdreview.platform.commerce.CommerceRequests.UpdateOrderStatusRequest;
import com.bdreview.platform.commerce.CommerceResponses.OrderResponse;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Orders. Placement and the customer's own history/cancel live under
 * {@code /api/v1/orders*}; the owner's per-business order queue lives under
 * {@code /api/v1/businesses/{id}/orders}. All endpoints require authentication.
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderService orders;
    private final com.bdreview.platform.promo.PromoEventService promoEvents;

    public OrderController(OrderService orders, com.bdreview.platform.promo.PromoEventService promoEvents) {
        this.orders = orders;
        this.promoEvents = promoEvents;
    }

    // ---- customer ----------------------------------------------------

    @PostMapping("/businesses/{businessId}/orders")
    public OrderResponse place(@PathVariable UUID businessId, @Valid @RequestBody PlaceOrderRequest req,
                               // V58 promo attribution — set when the order came from a business post / share link
                               @RequestParam(required = false) UUID promoPostId,
                               @RequestParam(required = false) UUID promoBoostId,
                               @RequestParam(required = false) String promoRef) {
        OrderResponse order = orders.placeOrder(CurrentUser.id(), businessId, req);
        promoEvents.recordConversion(new com.bdreview.platform.promo.PromoEventService.Attribution(promoPostId, promoBoostId, promoRef),
                com.bdreview.platform.promo.PromoEnums.PromoEventType.ORDER, order.id(), businessId);
        return order;
    }

    @GetMapping("/orders/mine")
    public PageResponse<OrderResponse> mine(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(orders.myOrders(CurrentUser.id(), page, size));
    }

    @GetMapping("/orders/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return orders.getOrder(CurrentUser.id(), id);
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id) {
        return orders.customerCancel(CurrentUser.id(), id);
    }

    // ---- owner -----------------------------------------------------

    @GetMapping("/businesses/{businessId}/orders")
    public PageResponse<OrderResponse> ownerList(@PathVariable UUID businessId,
                                                 @RequestParam(required = false) OrderStatus status,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(orders.ownerOrders(CurrentUser.id(), businessId, status, page, size));
    }

    @GetMapping("/businesses/{businessId}/orders/pending-count")
    public long pendingCount(@PathVariable UUID businessId) {
        return orders.pendingCount(CurrentUser.id(), businessId);
    }

    @PatchMapping("/orders/{id}/status")
    public OrderResponse setStatus(@PathVariable UUID id, @Valid @RequestBody UpdateOrderStatusRequest req) {
        return orders.transition(CurrentUser.id(), id, req.status(), req.note());
    }
}
