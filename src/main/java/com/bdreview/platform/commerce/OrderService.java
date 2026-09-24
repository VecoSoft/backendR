package com.bdreview.platform.commerce;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.catalog.MenuItem;
import com.bdreview.platform.catalog.MenuItemRepository;
import com.bdreview.platform.commerce.CommerceRequests.PlaceOrderRequest;
import com.bdreview.platform.commerce.CommerceRequests.PlaceOrderRequest.OrderLine;
import com.bdreview.platform.commerce.CommerceResponses.OrderItemResponse;
import com.bdreview.platform.commerce.CommerceResponses.OrderResponse;
import com.bdreview.platform.commerce.CommerceResponses.OrderStatusEventResponse;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.notification.NotificationType;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.offer.OfferType;
import org.locationtech.jts.geom.Point;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The heart of Phase A. Every amount is recomputed from the database here —
 * the client's numbers are display estimates only. Item names + unit prices +
 * customer name/phone are snapshotted so later edits never rewrite an order.
 */
@Service
public class OrderService {

    /** Fallback when the business hasn't set a default prep time (CommerceSettings#defaultPrepMinutes is null). */
    private static final int DEFAULT_PREP_MINUTES = 20;

    private final BusinessOrderRepository orderRepo;
    private final BusinessOrderItemRepository itemRepo;
    private final OrderStatusEventRepository eventRepo;
    private final MenuItemRepository menuRepo;
    private final OfferRepository offerRepo;
    private final CommerceSettingsService settingsService;
    private final DeliveryZoneService zoneService;
    private final CommerceGuard guard;
    private final CommerceNotifier notifier;

    public OrderService(BusinessOrderRepository orderRepo, BusinessOrderItemRepository itemRepo,
                        OrderStatusEventRepository eventRepo, MenuItemRepository menuRepo,
                        OfferRepository offerRepo,
                        CommerceSettingsService settingsService, DeliveryZoneService zoneService,
                        CommerceGuard guard, CommerceNotifier notifier) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.eventRepo = eventRepo;
        this.menuRepo = menuRepo;
        this.offerRepo = offerRepo;
        this.settingsService = settingsService;
        this.zoneService = zoneService;
        this.guard = guard;
        this.notifier = notifier;
    }

    // ================================================================
    // Placement
    // ================================================================
    @Transactional
    public OrderResponse placeOrder(UUID customerUserId, UUID businessId, PlaceOrderRequest req) {
        Business business = guard.getLiveOrThrow(businessId);
        BusinessCommerceSettings settings = settingsService.requireOrderingLive(businessId);

        requireFulfilmentEnabled(settings, req.fulfillmentType());
        if (!settings.supportsPayment(req.paymentMethod())) {
            throw new BadRequestException("That payment method isn't available for this business.");
        }

        // Merge duplicate lines, then price every line off the live menu row.
        Map<UUID, Integer> qtyById = new LinkedHashMap<>();
        for (OrderLine line : req.items()) {
            qtyById.merge(line.menuItemId(), line.quantity(), Integer::sum);
        }

        // A menu item can be linked to an offer (see V45's migration) — while that offer is
        // ACTIVE, this business's orders charge its discounted price instead of the item's own
        // listed price. Batched once per business rather than a query per line.
        Map<UUID, Offer> activeOfferByMenuItemId = offerRepo.findByBusinessIdAndMenuItemIdIsNotNull(businessId)
                .stream()
                .filter(Offer::isCurrentlyActive)
                .collect(java.util.stream.Collectors.toMap(Offer::getMenuItemId, o -> o, (a, b) -> a));

        List<BusinessOrderItem> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Map.Entry<UUID, Integer> e : qtyById.entrySet()) {
            MenuItem mi = menuRepo.findById(e.getKey())
                    .filter(m -> m.getBusinessId().equals(businessId))
                    .orElseThrow(() -> new BadRequestException("A menu item in your cart no longer exists."));
            // Orderable = the item is available AND has a positive numeric price.
            // (The legacy per-item ordering_enabled flag is no longer a gate — a
            // priced, available item is orderable once the business takes orders.)
            if (!mi.isAvailable() || mi.getPrice() == null || mi.getPrice().signum() <= 0) {
                throw new BadRequestException("\"" + mi.getName() + "\" is not available for ordering right now.");
            }
            Offer activeOffer = activeOfferByMenuItemId.get(mi.getId());
            BigDecimal unit = money(activeOffer != null && activeOffer.getOfferPrice() != null
                    ? activeOffer.getOfferPrice() : mi.getPrice());
            int qty = e.getValue();
            // BUY_ONE_GET_ONE has no discounted unit price (offerPrice is null for it) — the
            // discount is in how many units get billed: every pair costs one. Odd quantities
            // round the billed count up (3 bought -> 2 billed), never in the customer's favor.
            boolean isBogo = activeOffer != null && activeOffer.getOfferType() == OfferType.BUY_ONE_GET_ONE;
            int billedQty = isBogo ? (qty + 1) / 2 : qty;
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(billedQty));
            subtotal = subtotal.add(lineTotal);
            items.add(BusinessOrderItem.builder()
                    .sourceType(OrderItemSource.MENU_ITEM)
                    .sourceItemId(mi.getId())
                    .itemNameSnapshot(mi.getName())
                    .unitPriceSnapshot(unit)
                    .quantity(qty)
                    .totalPrice(lineTotal)
                    .build());
        }
        subtotal = money(subtotal);

        BigDecimal deliveryFee = BigDecimal.ZERO;
        Point deliveryLocation = null;
        BigDecimal deliveryDistanceKm = null;
        UUID deliveryZoneId = null;
        String deliveryAddress = null;

        if (req.fulfillmentType() == FulfillmentType.OWN_DELIVERY) {
            if (req.deliveryLat() == null || req.deliveryLng() == null
                    || req.deliveryAddress() == null || req.deliveryAddress().isBlank()) {
                throw new BadRequestException("A delivery address and map location are required for delivery.");
            }
            double[] dist = new double[1];
            DeliveryZone zone = zoneService.resolveZoneOrThrow(businessId, req.deliveryLat(), req.deliveryLng(), dist);
            if (subtotal.compareTo(zone.getMinimumOrderAmount()) < 0) {
                throw new BadRequestException("Minimum order for delivery is " + strip(zone.getMinimumOrderAmount())
                        + ". Add " + strip(zone.getMinimumOrderAmount().subtract(subtotal)) + " more.");
            }
            deliveryFee = money(zone.getDeliveryFee());
            deliveryLocation = GeoPoints.of(req.deliveryLat(), req.deliveryLng());
            deliveryDistanceKm = BigDecimal.valueOf(dist[0]).setScale(2, RoundingMode.HALF_UP);
            deliveryZoneId = zone.getId();
            deliveryAddress = req.deliveryAddress().trim();
        }

        BigDecimal total = money(subtotal.add(deliveryFee));

        BusinessOrder order = orderRepo.save(BusinessOrder.builder()
                .businessId(businessId)
                .customerUserId(customerUserId)
                .orderNumber("J-" + orderRepo.nextOrderNumber())
                .status(OrderStatus.PENDING)
                .fulfillmentType(req.fulfillmentType())
                .subtotal(subtotal)
                .deliveryFee(deliveryFee)
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(total)
                .paymentMethod(req.paymentMethod())
                .paymentStatus(PaymentStatus.UNPAID)
                .customerNameSnapshot(req.customerName().trim())
                .customerPhoneSnapshot(req.customerPhone().trim())
                .deliveryAddress(deliveryAddress)
                .deliveryLocation(deliveryLocation)
                .deliveryDistanceKm(deliveryDistanceKm)
                .deliveryZoneId(deliveryZoneId)
                .customerNote(blankToNull(req.customerNote()))
                .build());

        for (BusinessOrderItem it : items) {
            it.setOrderId(order.getId());
        }
        itemRepo.saveAll(items);
        eventRepo.save(OrderStatusEvent.builder()
                .orderId(order.getId()).fromStatus(null).toStatus(OrderStatus.PENDING)
                .actorUserId(customerUserId).note("Order placed").build());

        notifier.newOrder(business.getOwnerUserId(), order.getId(), order.getOrderNumber(),
                order.getCustomerNameSnapshot());

        return toResponse(order, business, items, List.of(
                new OrderStatusEventResponse(null, OrderStatus.PENDING, "Order placed", order.getCreatedAt())));
    }

    // ================================================================
    // Customer reads / cancel
    // ================================================================
    @Transactional(readOnly = true)
    public Page<OrderResponse> myOrders(UUID customerUserId, int page, int size) {
        Page<BusinessOrder> orders = orderRepo.findByCustomerUserIdOrderByCreatedAtDesc(
                customerUserId, PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size)));
        return mapPageWithItems(orders);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID requesterUserId, UUID orderId) {
        BusinessOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        Business business = guard.getLiveOrThrow(order.getBusinessId());
        boolean allowed = order.getCustomerUserId().equals(requesterUserId)
                || business.getOwnerUserId().equals(requesterUserId)
                || CurrentUser.hasRole("ADMIN");
        if (!allowed) {
            throw new ForbiddenException("You cannot view this order.");
        }
        return toResponse(order, business, itemRepo.findByOrderId(orderId),
                eventRepo.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                        .map(OrderStatusEventResponse::from).toList());
    }

    @Transactional
    public OrderResponse customerCancel(UUID customerUserId, UUID orderId) {
        BusinessOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getCustomerUserId().equals(customerUserId)) {
            throw new ForbiddenException("You cannot cancel this order.");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BadRequestException("Only a pending order can be cancelled.");
        }
        applyTransition(order, OrderStatus.CANCELLED, customerUserId, "Cancelled by customer");
        Business business = guard.getLiveOrThrow(order.getBusinessId());
        return toResponse(order, business, itemRepo.findByOrderId(orderId), timeline(orderId));
    }

    // ================================================================
    // Owner reads / transitions
    // ================================================================
    @Transactional(readOnly = true)
    public Page<OrderResponse> ownerOrders(UUID ownerUserId, UUID businessId, OrderStatus status, int page, int size) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        PageRequest pr = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<BusinessOrder> orders = status == null
                ? orderRepo.findByBusinessIdOrderByCreatedAtDesc(businessId, pr)
                : orderRepo.findByBusinessIdAndStatusOrderByCreatedAtDesc(businessId, status, pr);
        return mapPageWithItems(orders);
    }

    @Transactional(readOnly = true)
    public long pendingCount(UUID ownerUserId, UUID businessId) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        return orderRepo.countByBusinessIdAndStatus(businessId, OrderStatus.PENDING);
    }

    @Transactional
    public OrderResponse transition(UUID ownerUserId, UUID orderId, OrderStatus target, String note) {
        BusinessOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        Business business = guard.getOwnedOrThrow(ownerUserId, order.getBusinessId());

        OrderStatus from = order.getStatus();
        if (!from.canTransitionTo(target)) {
            throw new BadRequestException("Can't move an order from " + human(from) + " to " + human(target) + ".");
        }
        if (target == OrderStatus.READY_FOR_PICKUP && order.getFulfillmentType() != FulfillmentType.PICKUP) {
            throw new BadRequestException("This is a delivery order.");
        }
        if (target == OrderStatus.OUT_FOR_DELIVERY && order.getFulfillmentType() != FulfillmentType.OWN_DELIVERY) {
            throw new BadRequestException("This is a pickup order.");
        }
        if (target == OrderStatus.REJECTED) {
            order.setRejectionReason(blankToNull(note));
        }
        if (target == OrderStatus.COMPLETED) {
            order.setPaymentStatus(PaymentStatus.PAID);
        }
        if (target == OrderStatus.ACCEPTED && order.getEstimatedReadyAt() == null) {
            Integer prepMinutes = settingsService.getOrDefault(order.getBusinessId()).getDefaultPrepMinutes();
            order.setEstimatedReadyAt(Instant.now().plusSeconds(60L * (prepMinutes != null ? prepMinutes : DEFAULT_PREP_MINUTES)));
        }
        applyTransition(order, target, ownerUserId, note);

        NotificationType type = switch (target) {
            case ACCEPTED -> NotificationType.ORDER_ACCEPTED;
            case REJECTED -> NotificationType.ORDER_REJECTED;
            default -> NotificationType.ORDER_STATUS_CHANGED;
        };
        if (EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.REJECTED, OrderStatus.READY_FOR_PICKUP,
                OrderStatus.OUT_FOR_DELIVERY, OrderStatus.PICKED_UP, OrderStatus.DELIVERED,
                OrderStatus.COMPLETED).contains(target)) {
            notifier.statusChanged(order.getCustomerUserId(), order.getId(), order.getOrderNumber(), target, type);
        }

        return toResponse(order, business, itemRepo.findByOrderId(orderId), timeline(orderId));
    }

    /**
     * Sweeps orders the owner never actioned — still PENDING ("New") a full day after
     * placement — and auto-cancels them with the customer notified, instead of leaving
     * a stale order sitting forever with no resolution on either side.
     */
    @Transactional
    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.MINUTES)
    public void autoExpireStalePendingOrders() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        for (BusinessOrder order : orderRepo.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff)) {
            order.setRejectionReason("Auto-cancelled — the business didn't respond in time");
            applyTransition(order, OrderStatus.CANCELLED, null, "Auto-cancelled — no response within 24 hours");
            notifier.statusChanged(order.getCustomerUserId(), order.getId(), order.getOrderNumber(),
                    OrderStatus.CANCELLED, NotificationType.ORDER_STATUS_CHANGED);
        }
    }

    // ================================================================
    // Helpers
    // ================================================================
    private void applyTransition(BusinessOrder order, OrderStatus target, UUID actor, String note) {
        OrderStatus from = order.getStatus();
        order.setStatus(target);
        orderRepo.save(order);
        eventRepo.save(OrderStatusEvent.builder()
                .orderId(order.getId()).fromStatus(from).toStatus(target)
                .actorUserId(actor).note(blankToNull(note)).build());
    }

    private void requireFulfilmentEnabled(BusinessCommerceSettings s, FulfillmentType type) {
        boolean ok = (type == FulfillmentType.PICKUP && s.isPickupEnabled())
                || (type == FulfillmentType.OWN_DELIVERY && s.isOwnDeliveryEnabled());
        if (!ok) {
            throw new BadRequestException("That fulfilment option isn't available for this business.");
        }
    }

    private List<OrderStatusEventResponse> timeline(UUID orderId) {
        return eventRepo.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                .map(OrderStatusEventResponse::from).toList();
    }

    private Page<OrderResponse> mapPageWithItems(Page<BusinessOrder> orders) {
        List<UUID> ids = orders.getContent().stream().map(BusinessOrder::getId).toList();
        Map<UUID, List<BusinessOrderItem>> itemsByOrder = new HashMap<>();
        if (!ids.isEmpty()) {
            for (BusinessOrderItem it : itemRepo.findByOrderIdIn(ids)) {
                itemsByOrder.computeIfAbsent(it.getOrderId(), k -> new ArrayList<>()).add(it);
            }
        }
        Map<UUID, Business> businessCache = new HashMap<>();
        return orders.map(o -> {
            Business b = businessCache.computeIfAbsent(o.getBusinessId(), guard::getLiveOrThrow);
            return toResponse(o, b, itemsByOrder.getOrDefault(o.getId(), List.of()), null);
        });
    }

    private OrderResponse toResponse(BusinessOrder o, Business business,
                                     List<BusinessOrderItem> items, List<OrderStatusEventResponse> timeline) {
        Double lat = o.getDeliveryLocation() != null ? o.getDeliveryLocation().getY() : null;
        Double lng = o.getDeliveryLocation() != null ? o.getDeliveryLocation().getX() : null;
        return new OrderResponse(
                o.getId(), o.getOrderNumber(), o.getBusinessId(), business.getName(), business.getSlug(),
                business.getContactNumber(), business.getArea().getName() + ", " + business.getCity().getName(),
                o.getCustomerUserId(), o.getStatus(), o.getFulfillmentType(),
                o.getSubtotal(), o.getDeliveryFee(), o.getDiscountAmount(), o.getTotalAmount(),
                o.getPaymentMethod(), o.getPaymentStatus(),
                o.getCustomerNameSnapshot(), o.getCustomerPhoneSnapshot(),
                o.getDeliveryAddress(), lat, lng, o.getDeliveryDistanceKm(),
                o.getCustomerNote(), o.getRejectionReason(), o.getEstimatedReadyAt(), o.getCreatedAt(),
                items.stream().map(OrderItemResponse::from).toList(),
                timeline);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String strip(BigDecimal v) {
        return "৳" + v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String human(OrderStatus s) {
        return s.name().toLowerCase().replace('_', ' ');
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
