package com.bdreview.platform.admin.controller;

import com.bdreview.platform.admin.service.AdminCommerceService;
import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.adminconfig.AdminConfigService;
import com.bdreview.platform.adminconfig.CommerceConfig;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.commerce.BookingStatus;
import com.bdreview.platform.commerce.OrderStatus;
import com.bdreview.platform.commerce.PaymentMethod;
import com.bdreview.platform.offer.OfferStatus;
import com.bdreview.platform.offer.OfferType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

/** Admin → Commerce (V65): orders, bookings, offers across every business, plus the commerce settings. ADMIN only. */
@Controller
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_COMMERCE')")
@RequestMapping("/admin/commerce")
public class AdminCommerceController {

    private final AdminCommerceService commerce;
    private final AdminConfigService adminConfig;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public AdminCommerceController(AdminCommerceService commerce, AdminConfigService adminConfig,
                                   BusinessRepository businessRepository, UserRepository userRepository) {
        this.commerce = commerce;
        this.adminConfig = adminConfig;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
    }

    // ---------------------------------------------------------------- overview + settings

    @GetMapping
    public String overview(Model model) {
        model.addAttribute("config", adminConfig.commerce());
        model.addAttribute("defaults", new CommerceConfig());
        model.addAttribute("stuckOrders", commerce.orders(new AdminCommerceService.Filter(null, null, null, null, null, 30, null, false), 0).getTotalElements());
        model.addAttribute("flaggedOffers", commerce.offers(new AdminCommerceService.Filter(null, null, null, null, null, null, null, true), 0).getTotalElements());
        model.addAttribute("active", "commerce");
        return "admin/commerce/index";
    }

    @PostMapping("/settings")
    public String saveSettings(@RequestParam int orderAutoCancelMinutes, @RequestParam int bookingNoShowGraceMinutes,
                               @RequestParam int maxActiveOffersPerBusiness, @RequestParam(required = false) String reason,
                               RedirectAttributes ra) {
        return act(ra, "/admin/commerce", () -> {
            CommerceConfig c = adminConfig.commerce();
            c.setOrderAutoCancelMinutes(orderAutoCancelMinutes);
            c.setBookingNoShowGraceMinutes(bookingNoShowGraceMinutes);
            c.setMaxActiveOffersPerBusiness(maxActiveOffersPerBusiness);
            adminConfig.saveCommerce(c, reason);
            return "Commerce settings saved — the background jobs use them from their next run.";
        });
    }

    // ---------------------------------------------------------------- orders

    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) String status, @RequestParam(required = false) String business,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) String paymentMethod,
                         @RequestParam(required = false) Integer stuckMinutes,
                         @RequestParam(required = false) Integer page, Model model) {
        var filter = new AdminCommerceService.Filter(blank(status), blank(business), from, to, blank(paymentMethod), stuckMinutes, null, false);
        model.addAttribute("results", commerce.orders(filter, AdminSupport.pageOrDefault(page)));
        model.addAttribute("f", filter);
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("active", "c-orders");
        return "admin/commerce/orders";
    }

    @GetMapping("/orders/{id}")
    public String order(@PathVariable UUID id, Model model) {
        var order = commerce.order(id);
        model.addAttribute("order", order);
        model.addAttribute("items", commerce.orderItems(id));
        model.addAttribute("timeline", commerce.orderTimeline(id));
        model.addAttribute("offers", commerce.appliedOffers(id));
        var business = businessRepository.findById(order.getBusinessId()).orElse(null);
        model.addAttribute("business", business);
        model.addAttribute("owner", business == null ? null : userRepository.findById(business.getOwnerUserId()).orElse(null));
        model.addAttribute("customer", userRepository.findById(order.getCustomerUserId()).orElse(null));
        model.addAttribute("active", "c-orders");
        return "admin/commerce/order";
    }

    @PostMapping("/orders/{id}/cancel")
    public String cancelOrder(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/commerce/orders/" + id, () -> {
            commerce.cancelOrder(id, reason);
            return "Order cancelled — the customer and the business were notified.";
        });
    }

    @PostMapping("/orders/{id}/resolve-dispute")
    public String resolveDispute(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/commerce/orders/" + id, () -> {
            commerce.resolveDispute(id, reason);
            return "Dispute marked resolved.";
        });
    }

    // ---------------------------------------------------------------- bookings

    @GetMapping("/bookings")
    public String bookings(@RequestParam(required = false) String status, @RequestParam(required = false) String business,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) Integer page, Model model) {
        var filter = new AdminCommerceService.Filter(blank(status), blank(business), from, to, null, null, null, false);
        model.addAttribute("results", commerce.bookings(filter, AdminSupport.pageOrDefault(page)));
        model.addAttribute("f", filter);
        model.addAttribute("statuses", BookingStatus.values());
        model.addAttribute("active", "c-bookings");
        return "admin/commerce/bookings";
    }

    @GetMapping("/bookings/{id}")
    public String booking(@PathVariable UUID id, Model model) {
        var booking = commerce.booking(id);
        model.addAttribute("booking", booking);
        model.addAttribute("timeline", commerce.bookingTimeline(id));
        var business = businessRepository.findById(booking.getBusinessId()).orElse(null);
        model.addAttribute("business", business);
        model.addAttribute("owner", business == null ? null : userRepository.findById(business.getOwnerUserId()).orElse(null));
        model.addAttribute("customer", userRepository.findById(booking.getCustomerUserId()).orElse(null));
        model.addAttribute("active", "c-bookings");
        return "admin/commerce/booking";
    }

    @PostMapping("/bookings/{id}/{action}")
    public String bookingAction(@PathVariable UUID id, @PathVariable String action,
                                @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/commerce/bookings/" + id, () -> {
            BookingStatus target = switch (action) {
                case "cancel" -> BookingStatus.CANCELLED;
                case "no-show" -> BookingStatus.NO_SHOW;
                case "complete" -> BookingStatus.COMPLETED;
                default -> throw new com.bdreview.platform.common.BadRequestException("Unknown action.");
            };
            commerce.setBookingStatus(id, target, reason);
            return "Booking updated — the customer and the business were notified.";
        });
    }

    // ---------------------------------------------------------------- offers

    @GetMapping("/offers")
    public String offers(@RequestParam(required = false) String status, @RequestParam(required = false) String business,
                         @RequestParam(required = false) String type, @RequestParam(defaultValue = "false") boolean flagged,
                         @RequestParam(required = false) Integer page, Model model) {
        var filter = new AdminCommerceService.Filter(blank(status), blank(business), null, null, null, null, blank(type), flagged);
        model.addAttribute("results", commerce.offers(filter, AdminSupport.pageOrDefault(page)));
        model.addAttribute("f", filter);
        model.addAttribute("statuses", OfferStatus.values());
        model.addAttribute("types", OfferType.values());
        model.addAttribute("active", "c-offers");
        return "admin/commerce/offers";
    }

    @PostMapping("/offers/{id}/{action}")
    public String offerAction(@PathVariable UUID id, @PathVariable String action, @RequestParam(required = false) String reason,
                              @RequestParam(required = false) String back, RedirectAttributes ra) {
        String target = back != null && back.startsWith("/admin/") ? back : "/admin/commerce/offers";
        return act(ra, target, () -> {
            switch (action) {
                case "end" -> commerce.endOffer(id, reason);
                case "hide" -> commerce.hideOffer(id, reason);
                default -> throw new com.bdreview.platform.common.BadRequestException("Unknown action.");
            }
            return "Offer updated — the business owner was notified.";
        });
    }

    // ----------------------------------------------------------------

    private static String act(RedirectAttributes ra, String back, Supplier<String> action) {
        try {
            ra.addFlashAttribute("successMessage", action.get());
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + back;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
