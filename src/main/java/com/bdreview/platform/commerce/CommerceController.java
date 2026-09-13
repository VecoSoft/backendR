package com.bdreview.platform.commerce;

import com.bdreview.platform.commerce.CommerceRequests.*;
import com.bdreview.platform.commerce.CommerceResponses.CommerceSettingsResponse;
import com.bdreview.platform.commerce.CommerceResponses.DeliveryQuoteResponse;
import com.bdreview.platform.commerce.CommerceResponses.PublicCommerceView;
import com.bdreview.platform.common.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Commerce settings + delivery zones for one business. {@code GET /commerce}
 * and {@code GET /delivery-zones} are public (they ride the existing
 * {@code GET /api/v1/businesses/**} allow-list); every write and the checkout
 * quote require authentication.
 */
@RestController
@RequestMapping("/api/v1/businesses/{businessId}")
public class CommerceController {

    private final CommerceSettingsService settings;
    private final DeliveryZoneService zones;

    public CommerceController(CommerceSettingsService settings, DeliveryZoneService zones) {
        this.settings = settings;
        this.zones = zones;
    }

    // ---- settings ------------------------------------------------------

    @GetMapping("/commerce")
    public PublicCommerceView publicCommerce(@PathVariable UUID businessId) {
        return settings.publicView(businessId);
    }

    @GetMapping("/commerce/manage")
    public CommerceSettingsResponse ownerCommerce(@PathVariable UUID businessId) {
        return settings.ownerView(CurrentUser.id(), businessId);
    }

    @PutMapping("/commerce")
    public CommerceSettingsResponse updateCommerce(@PathVariable UUID businessId,
                                                   @Valid @RequestBody CommerceSettingsRequest req) {
        return settings.upsert(CurrentUser.id(), businessId, req);
    }

    @PutMapping("/commerce/accepting")
    public CommerceSettingsResponse setAccepting(@PathVariable UUID businessId,
                                                 @Valid @RequestBody AcceptingOrdersRequest req) {
        return settings.setAccepting(CurrentUser.id(), businessId, req);
    }

    /** Phase C — owner on/off switch for online booking (currently Salon & Beauty only). */
    @PutMapping("/commerce/booking")
    public CommerceSettingsResponse setBooking(@PathVariable UUID businessId,
                                               @Valid @RequestBody BookingSettingsRequest req) {
        return settings.setBooking(CurrentUser.id(), businessId, req);
    }

    // ---- delivery zones ---------------------------------------------

    @GetMapping("/delivery-zones")
    public List<DeliveryZone> listZones(@PathVariable UUID businessId) {
        return zones.list(businessId);
    }

    @PostMapping("/delivery-zones")
    public DeliveryZone addZone(@PathVariable UUID businessId, @Valid @RequestBody DeliveryZoneRequest req) {
        return zones.add(CurrentUser.id(), businessId, req);
    }

    @PutMapping("/delivery-zones/{id}")
    public DeliveryZone updateZone(@PathVariable UUID businessId, @PathVariable UUID id,
                                   @Valid @RequestBody DeliveryZoneRequest req) {
        return zones.update(CurrentUser.id(), businessId, id, req);
    }

    @DeleteMapping("/delivery-zones/{id}")
    public ResponseEntity<Void> deleteZone(@PathVariable UUID businessId, @PathVariable UUID id) {
        zones.delete(CurrentUser.id(), businessId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/delivery-zones/reorder")
    public List<DeliveryZone> reorderZones(@PathVariable UUID businessId,
                                           @Valid @RequestBody ReorderZonesRequest req) {
        return zones.reorder(CurrentUser.id(), businessId, req.orderedIds());
    }

    // ---- checkout quote (authenticated customer) --------------------

    @PostMapping("/delivery-quote")
    public DeliveryQuoteResponse deliveryQuote(@PathVariable UUID businessId,
                                               @Valid @RequestBody DeliveryQuoteRequest req) {
        CurrentUser.id(); // must be logged in
        return zones.quote(businessId, req.lat(), req.lng());
    }
}
