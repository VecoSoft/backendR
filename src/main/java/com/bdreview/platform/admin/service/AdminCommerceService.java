package com.bdreview.platform.admin.service;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.commerce.*;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.listing.AdminNotifier;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.offer.OfferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Admin → Commerce (V65): cross-business order, booking and offer oversight. Lists are plain SQL
 * with optional filters; every action needs a reason, is audited with before/after and notifies
 * the people affected (customer and/or business owner).
 */
@Service
public class AdminCommerceService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    private static final int PAGE_SIZE = 25;

    /** Order/booking list filters (all optional). */
    public record Filter(String status, String business, LocalDate from, LocalDate to, String paymentMethod,
                         Integer stuckMinutes, String offerType, boolean flaggedOnly) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final BusinessOrderRepository orderRepository;
    private final BusinessOrderItemRepository orderItemRepository;
    private final OrderStatusEventRepository orderEventRepository;
    private final BookingRepository bookingRepository;
    private final BookingStatusEventRepository bookingEventRepository;
    private final OfferRepository offerRepository;
    private final BusinessRepository businessRepository;
    private final AuditLogService auditLogService;
    private final AdminNotifier notifier;

    public AdminCommerceService(NamedParameterJdbcTemplate jdbc, BusinessOrderRepository orderRepository,
                                BusinessOrderItemRepository orderItemRepository, OrderStatusEventRepository orderEventRepository,
                                BookingRepository bookingRepository, BookingStatusEventRepository bookingEventRepository,
                                OfferRepository offerRepository, BusinessRepository businessRepository,
                                AuditLogService auditLogService, AdminNotifier notifier) {
        this.jdbc = jdbc;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderEventRepository = orderEventRepository;
        this.bookingRepository = bookingRepository;
        this.bookingEventRepository = bookingEventRepository;
        this.offerRepository = offerRepository;
        this.businessRepository = businessRepository;
        this.auditLogService = auditLogService;
        this.notifier = notifier;
    }

    // =================================================================
    // Orders
    // =================================================================

    public Page<Map<String, Object>> orders(Filter f, int page) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (notBlank(f.status())) {
            where.append(" AND o.status = :status");
            p.addValue("status", f.status());
        }
        if (notBlank(f.paymentMethod())) {
            where.append(" AND o.payment_method = :pm");
            p.addValue("pm", f.paymentMethod());
        }
        businessFilter(f, where, p, "o");
        dateFilter(f, where, p, "o.created_at");
        if (f.stuckMinutes() != null && f.stuckMinutes() > 0) {
            where.append(" AND o.status IN ('PENDING','ACCEPTED') AND o.created_at < now() - make_interval(mins => :stuck)");
            p.addValue("stuck", f.stuckMinutes());
        }
        String from = """
                FROM business_order o JOIN business b ON b.id = o.business_id
                """ + where;
        return page("""
                SELECT o.id, o.order_number, o.status, o.payment_method, o.payment_status, o.total_amount, o.discount_amount,
                       o.fulfillment_type, o.created_at, o.customer_name_snapshot, o.dispute_resolved_at,
                       b.id AS business_id, b.name AS business_name,
                       round(extract(epoch FROM now() - o.created_at) / 60) AS age_minutes
                """ + from + " ORDER BY o.created_at DESC", from, p, page);
    }

    public BusinessOrder order(UUID id) {
        return orderRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    public List<BusinessOrderItem> orderItems(UUID orderId) {
        return orderItemRepository.findByOrderId(orderId);
    }

    public List<OrderStatusEvent> orderTimeline(UUID orderId) {
        return orderEventRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    /** Offers applied to any line of this order (title + type), for the detail page. */
    public List<Offer> appliedOffers(UUID orderId) {
        Set<UUID> ids = new LinkedHashSet<>();
        orderItemRepository.findByOrderId(orderId).forEach(i -> {
            if (i.getOfferId() != null) {
                ids.add(i.getOfferId());
            }
        });
        return offerRepository.findAllById(ids);
    }

    @Transactional
    public void cancelOrder(UUID orderId, String reason) {
        String why = requireReason(reason);
        BusinessOrder order = order(orderId);
        if (order.getStatus().isTerminal()) {
            throw new BadRequestException("This order is already " + order.getStatus().name().toLowerCase() + ".");
        }
        OrderStatus before = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        order.setRejectionReason("Cancelled by support: " + why);
        orderRepository.save(order);
        orderEventRepository.save(OrderStatusEvent.builder()
                .orderId(orderId).fromStatus(before).toStatus(OrderStatus.CANCELLED)
                .actorUserId(CurrentUser.idOrNull()).note(truncate("Cancelled by support: " + why, 200)).build());
        orderItemRepository.findByOrderId(orderId).stream().map(BusinessOrderItem::getOfferId).filter(Objects::nonNull)
                .distinct().forEach(offerId -> offerRepository.adjustRedemptionCount(offerId, -1));
        auditLogService.record("ORDER", orderId, "ORDER_CANCELLED_BY_ADMIN", why,
                Map.of("status", before.name()), Map.of("status", OrderStatus.CANCELLED.name()));
        Business business = businessRepository.findById(order.getBusinessId()).orElse(null);
        String msg = "Order " + order.getOrderNumber() + " was cancelled by Jachai support: " + why;
        notifier.notify(order.getCustomerUserId(), "Order " + order.getOrderNumber() + " cancelled", msg, "ORDER", orderId);
        if (business != null) {
            notifier.notify(business.getOwnerUserId(), "Order " + order.getOrderNumber() + " cancelled", msg, "ORDER", orderId);
        }
    }

    @Transactional
    public void resolveDispute(UUID orderId, String notes) {
        String why = requireReason(notes);
        BusinessOrder order = order(orderId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("disputeResolvedAt", order.getDisputeResolvedAt() == null ? null : order.getDisputeResolvedAt().toString());
        before.put("disputeNote", order.getDisputeNote());
        order.setDisputeResolvedAt(Instant.now());
        order.setDisputeResolvedBy(CurrentUser.idOrNull());
        order.setDisputeNote(why);
        orderRepository.save(order);
        auditLogService.record("ORDER", orderId, "ORDER_DISPUTE_RESOLVED", why, before,
                Map.of("disputeResolvedAt", order.getDisputeResolvedAt().toString(), "disputeNote", why));
    }

    // =================================================================
    // Bookings
    // =================================================================

    public Page<Map<String, Object>> bookings(Filter f, int page) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (notBlank(f.status())) {
            where.append(" AND k.status = :status");
            p.addValue("status", f.status());
        }
        businessFilter(f, where, p, "k");
        if (f.from() != null) {
            where.append(" AND k.slot_start >= :from");
            p.addValue("from", f.from().atStartOfDay());
        }
        if (f.to() != null) {
            where.append(" AND k.slot_start < :to");
            p.addValue("to", f.to().plusDays(1).atStartOfDay());
        }
        String from = " FROM business_booking k JOIN business b ON b.id = k.business_id" + where;
        return page("""
                SELECT k.id, k.booking_number, k.status, k.slot_start, k.slot_end, k.service_name_snapshot,
                       k.staff_name_snapshot, k.customer_name_snapshot, k.created_at,
                       b.id AS business_id, b.name AS business_name
                """ + from + " ORDER BY k.slot_start DESC", from, p, page);
    }

    public Booking booking(UUID id) {
        return bookingRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    public List<BookingStatusEvent> bookingTimeline(UUID bookingId) {
        return bookingEventRepository.findByBookingIdOrderByCreatedAtAsc(bookingId);
    }

    /** target: CANCELLED (from any open state), NO_SHOW or COMPLETED (from CONFIRMED). */
    @Transactional
    public void setBookingStatus(UUID bookingId, BookingStatus target, String reason) {
        String why = requireReason(reason);
        Booking booking = booking(bookingId);
        BookingStatus before = booking.getStatus();
        boolean allowed = switch (target) {
            case CANCELLED -> before == BookingStatus.PENDING || before == BookingStatus.CONFIRMED;
            case NO_SHOW, COMPLETED -> before == BookingStatus.CONFIRMED;
            default -> false;
        };
        if (!allowed) {
            throw new BadRequestException("A " + before.name().toLowerCase() + " booking can't be marked "
                    + target.name().toLowerCase().replace('_', '-') + ".");
        }
        booking.setStatus(target);
        if (target == BookingStatus.CANCELLED) {
            booking.setRejectionReason("Cancelled by support: " + why);
        }
        bookingRepository.save(booking);
        bookingEventRepository.save(BookingStatusEvent.builder()
                .bookingId(bookingId).fromStatus(before).toStatus(target)
                .actorUserId(CurrentUser.idOrNull()).note(truncate("Support: " + why, 200)).build());
        auditLogService.record("BOOKING", bookingId, "BOOKING_" + target.name() + "_BY_ADMIN", why,
                Map.of("status", before.name()), Map.of("status", target.name()));
        String label = switch (target) {
            case CANCELLED -> "cancelled";
            case NO_SHOW -> "marked as a no-show";
            default -> "marked completed";
        };
        String msg = "Booking " + booking.getBookingNumber() + " was " + label + " by Jachai support: " + why;
        notifier.notify(booking.getCustomerUserId(), "Booking " + booking.getBookingNumber() + " " + label, msg, "BOOKING", bookingId);
        businessRepository.findById(booking.getBusinessId()).ifPresent(b ->
                notifier.notify(b.getOwnerUserId(), "Booking " + booking.getBookingNumber() + " " + label, msg, "BOOKING", bookingId));
    }

    // =================================================================
    // Offers
    // =================================================================

    /** SQL flag expressions — an offer is "suspicious" when any is true. */
    private static final String FLAGS = """
            (o.offer_price IS NOT NULL AND o.original_price IS NOT NULL AND o.offer_price >= o.original_price) AS flag_price,
            ((o.offer_type = 'PERCENTAGE_DISCOUNT' AND o.discount_value > 90)
              OR (o.offer_type = 'FIXED_AMOUNT_DISCOUNT' AND o.original_price > 0 AND o.discount_value > o.original_price * 0.9)
              OR (o.offer_price IS NOT NULL AND o.original_price > 0 AND o.offer_price < o.original_price * 0.1)) AS flag_discount,
            (o.status = 'ACTIVE' AND o.valid_until < now()) AS flag_expired_active,
            (o.offer_type = 'BUY_ONE_GET_ONE' AND o.menu_item_id IS NULL) AS flag_bogo
            """;
    private static final String ANY_FLAG = """
            ((o.offer_price IS NOT NULL AND o.original_price IS NOT NULL AND o.offer_price >= o.original_price)
             OR (o.offer_type = 'PERCENTAGE_DISCOUNT' AND o.discount_value > 90)
             OR (o.offer_type = 'FIXED_AMOUNT_DISCOUNT' AND o.original_price > 0 AND o.discount_value > o.original_price * 0.9)
             OR (o.offer_price IS NOT NULL AND o.original_price > 0 AND o.offer_price < o.original_price * 0.1)
             OR (o.status = 'ACTIVE' AND o.valid_until < now())
             OR (o.offer_type = 'BUY_ONE_GET_ONE' AND o.menu_item_id IS NULL))""";

    /** status filter: ACTIVE (live), EXPIRED (ran its course), DRAFT, CANCELLED, REJECTED (incl. hidden), PENDING_APPROVAL. */
    public Page<Map<String, Object>> offers(Filter f, int page) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (notBlank(f.status())) {
            switch (f.status()) {
                case "ACTIVE" -> where.append(" AND o.status = 'ACTIVE' AND o.valid_until > now()");
                case "EXPIRED" -> where.append(" AND o.status = 'ACTIVE' AND o.valid_until <= now()");
                default -> {
                    where.append(" AND o.status = :status");
                    p.addValue("status", f.status());
                }
            }
        }
        if (notBlank(f.offerType())) {
            where.append(" AND o.offer_type = :type");
            p.addValue("type", f.offerType());
        }
        businessFilter(f, where, p, "o");
        if (f.flaggedOnly()) {
            where.append(" AND ").append(ANY_FLAG);
        }
        String from = " FROM offer o JOIN business b ON b.id = o.business_id" + where;
        return page("SELECT o.id, o.title, o.offer_type, o.status, o.discount_value, o.original_price, o.offer_price, "
                + "o.valid_from, o.valid_until, o.claim_count, o.redemption_count, o.rejection_reason, "
                + "b.id AS business_id, b.name AS business_name, " + FLAGS + from + " ORDER BY o.created_at DESC", from, p, page);
    }

    /** Ends a live offer now (it shows as expired from then on). */
    @Transactional
    public void endOffer(UUID offerId, String reason) {
        String why = requireReason(reason);
        Offer offer = offerRepository.findById(offerId).orElseThrow(() -> new ResourceNotFoundException("Offer not found"));
        if (offer.getStatus() != OfferStatus.ACTIVE || !offer.getValidUntil().isAfter(Instant.now())) {
            throw new BadRequestException("Only a live offer can be ended.");
        }
        Instant beforeUntil = offer.getValidUntil();
        offer.setValidUntil(Instant.now());
        offerRepository.save(offer);
        auditLogService.record("OFFER", offerId, "OFFER_ENDED_BY_ADMIN", why,
                Map.of("validUntil", beforeUntil.toString()), Map.of("validUntil", offer.getValidUntil().toString()));
        notifyOwner(offer, "Offer ended: " + offer.getTitle(),
                "Your offer \"" + offer.getTitle() + "\" was ended by Jachai support: " + why);
    }

    /** Takes an offer off every public surface (status REJECTED with the reason) — the owner can fix and resubmit. */
    @Transactional
    public void hideOffer(UUID offerId, String reason) {
        String why = requireReason(reason);
        Offer offer = offerRepository.findById(offerId).orElseThrow(() -> new ResourceNotFoundException("Offer not found"));
        if (offer.getStatus() == OfferStatus.REJECTED) {
            throw new BadRequestException("This offer is already hidden.");
        }
        OfferStatus before = offer.getStatus();
        offer.setStatus(OfferStatus.REJECTED);
        offer.setRejectionReason("Hidden by Jachai support: " + why);
        offerRepository.save(offer);
        auditLogService.record("OFFER", offerId, "OFFER_HIDDEN_BY_ADMIN", why,
                Map.of("status", before.name()), Map.of("status", OfferStatus.REJECTED.name()));
        notifyOwner(offer, "Offer hidden: " + offer.getTitle(),
                "Your offer \"" + offer.getTitle() + "\" was hidden by Jachai support: " + why
                        + " You can edit it and publish it again.");
    }

    // =================================================================

    private void notifyOwner(Offer offer, String title, String body) {
        businessRepository.findById(offer.getBusinessId())
                .ifPresent(b -> notifier.notify(b.getOwnerUserId(), title, body, "OFFER", offer.getId()));
    }

    private Page<Map<String, Object>> page(String select, String from, MapSqlParameterSource p, int page) {
        long total = Optional.ofNullable(jdbc.queryForObject("SELECT count(*) " + from, p, Long.class)).orElse(0L);
        p.addValue("limit", PAGE_SIZE).addValue("offset", (long) page * PAGE_SIZE);
        List<Map<String, Object>> rows = jdbc.queryForList(select + " LIMIT :limit OFFSET :offset", p);
        return new PageImpl<>(rows, PageRequest.of(page, PAGE_SIZE), total);
    }

    private static void businessFilter(Filter f, StringBuilder where, MapSqlParameterSource p, String alias) {
        if (!notBlank(f.business())) {
            return;
        }
        try {
            UUID id = UUID.fromString(f.business().trim());
            where.append(" AND ").append(alias).append(".business_id = :bid");
            p.addValue("bid", id);
        } catch (IllegalArgumentException notAnId) {
            where.append(" AND b.name ILIKE :bname");
            p.addValue("bname", "%" + f.business().trim() + "%");
        }
    }

    private static void dateFilter(Filter f, StringBuilder where, MapSqlParameterSource p, String column) {
        if (f.from() != null) {
            where.append(" AND ").append(column).append(" >= :from");
            p.addValue("from", java.sql.Timestamp.from(f.from().atStartOfDay(ZONE).toInstant()));
        }
        if (f.to() != null) {
            where.append(" AND ").append(column).append(" < :to");
            p.addValue("to", java.sql.Timestamp.from(f.to().plusDays(1).atStartOfDay(ZONE).toInstant()));
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 1000) {
            throw new BadRequestException("The reason can be at most 1000 characters.");
        }
        return trimmed;
    }
}
