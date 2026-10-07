package com.bdreview.platform.commerce;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.FeatureDisabledException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.features.FeatureFlagService;
import com.bdreview.platform.features.PlatformFeature;
import com.bdreview.platform.messaging.MessageService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * V70 "Message customer" on the owner's order and booking pages: the app no longer collects
 * customers' phone numbers, so the business reaches them through in-app chat. Returns the thread
 * (created if needed) for the owner to write in; the customer sees it in their messages.
 */
@RestController
@RequestMapping("/api/v1")
public class CustomerChatController {

    private final BusinessOrderRepository orders;
    private final BookingRepository bookings;
    private final CommerceGuard guard;
    private final MessageService messages;
    private final FeatureFlagService features;

    public CustomerChatController(BusinessOrderRepository orders, BookingRepository bookings, CommerceGuard guard,
                                  MessageService messages, FeatureFlagService features) {
        this.orders = orders;
        this.bookings = bookings;
        this.guard = guard;
        this.messages = messages;
        this.features = features;
    }

    @PostMapping("/orders/{id}/customer-chat")
    public Map<String, UUID> orderChat(@PathVariable UUID id) {
        BusinessOrder order = orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return open(order.getBusinessId(), order.getCustomerUserId());
    }

    @PostMapping("/bookings/{id}/customer-chat")
    public Map<String, UUID> bookingChat(@PathVariable UUID id) {
        Booking booking = bookings.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        return open(booking.getBusinessId(), booking.getCustomerUserId());
    }

    private Map<String, UUID> open(UUID businessId, UUID customerUserId) {
        if (!features.isEnabled(PlatformFeature.OWNER_CHAT)) {
            throw new FeatureDisabledException();
        }
        UUID owner = CurrentUser.id();
        guard.getOwnedOrThrow(owner, businessId);
        return Map.of("threadId", messages.openThreadAsOwner(owner, businessId, customerUserId).getId());
    }
}
