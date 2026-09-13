package com.bdreview.platform.commerce;

import com.bdreview.platform.commerce.CommerceRequests.DeliveryZoneRequest;
import com.bdreview.platform.commerce.CommerceResponses.DeliveryQuoteResponse;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Distance-band delivery zones + the checkout distance/fee quote. Distance is
 * PostGIS {@code ST_Distance} against the business's stored point — the same
 * source of truth is used again when the order is actually placed.
 */
@Service
public class DeliveryZoneService {

    private static final int MAX_ZONES = 10;
    private static final BigDecimal MAX_KM = BigDecimal.valueOf(50);

    private final DeliveryZoneRepository repo;
    private final CommerceGuard guard;

    public DeliveryZoneService(DeliveryZoneRepository repo, CommerceGuard guard) {
        this.repo = repo;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public List<DeliveryZone> list(UUID businessId) {
        return repo.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    @Transactional
    public DeliveryZone add(UUID ownerUserId, UUID businessId, DeliveryZoneRequest req) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        validate(req);
        long existing = repo.countByBusinessId(businessId);
        if (existing >= MAX_ZONES) {
            throw new BadRequestException("You can have at most " + MAX_ZONES + " delivery zones.");
        }
        return repo.save(DeliveryZone.builder()
                .businessId(businessId)
                .name(req.name().trim())
                .minDistanceKm(req.minDistanceKm())
                .maxDistanceKm(req.maxDistanceKm())
                .deliveryFee(req.deliveryFee())
                .minimumOrderAmount(req.minimumOrderAmount() == null ? BigDecimal.ZERO : req.minimumOrderAmount())
                .estimatedDeliveryMinutes(req.estimatedDeliveryMinutes())
                .active(req.active() == null || req.active())
                .sortOrder((int) existing)
                .build());
    }

    @Transactional
    public DeliveryZone update(UUID ownerUserId, UUID businessId, UUID id, DeliveryZoneRequest req) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        validate(req);
        DeliveryZone z = ownedZone(businessId, id);
        z.setName(req.name().trim());
        z.setMinDistanceKm(req.minDistanceKm());
        z.setMaxDistanceKm(req.maxDistanceKm());
        z.setDeliveryFee(req.deliveryFee());
        z.setMinimumOrderAmount(req.minimumOrderAmount() == null ? BigDecimal.ZERO : req.minimumOrderAmount());
        z.setEstimatedDeliveryMinutes(req.estimatedDeliveryMinutes());
        z.setActive(req.active() == null || req.active());
        return repo.save(z);
    }

    @Transactional
    public void delete(UUID ownerUserId, UUID businessId, UUID id) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        repo.deleteByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public List<DeliveryZone> reorder(UUID ownerUserId, UUID businessId, List<UUID> orderedIds) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        Map<UUID, DeliveryZone> byId = repo.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .collect(Collectors.toMap(DeliveryZone::getId, z -> z));
        if (!byId.keySet().equals(new HashSet<>(orderedIds))) {
            throw new BadRequestException("Reorder list must contain every zone id exactly once.");
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            byId.get(orderedIds.get(i)).setSortOrder(i);
        }
        repo.saveAll(byId.values());
        return repo.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    /**
     * Checkout quote for (lat,lng). {@code deliverable=false} carries the
     * farthest active zone's max km so the UI can say "6.2 km away · max 5 km".
     */
    @Transactional(readOnly = true)
    public DeliveryQuoteResponse quote(UUID businessId, double lat, double lng) {
        double km = distanceKm(businessId, lat, lng);
        List<DeliveryZone> zones = repo.findByBusinessIdAndActiveTrueOrderByMinDistanceKmAsc(businessId);
        return zones.stream()
                .filter(z -> z.covers(km))
                .findFirst()
                .map(z -> DeliveryQuoteResponse.deliverable(km, z))
                .orElseGet(() -> DeliveryQuoteResponse.notDeliverable(km, maxActiveKm(zones)));
    }

    /** Resolve the zone that will price a real order, or throw. */
    DeliveryZone resolveZoneOrThrow(UUID businessId, double lat, double lng, double[] outDistanceKm) {
        double km = distanceKm(businessId, lat, lng);
        if (outDistanceKm != null && outDistanceKm.length > 0) {
            outDistanceKm[0] = km;
        }
        List<DeliveryZone> zones = repo.findByBusinessIdAndActiveTrueOrderByMinDistanceKmAsc(businessId);
        return zones.stream().filter(z -> z.covers(km)).findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "This business does not deliver to your location (" + round2(km) + " km away)."));
    }

    private double distanceKm(UUID businessId, double lat, double lng) {
        Double metres = repo.distanceMetres(businessId, lat, lng);
        if (metres == null) {
            throw new ResourceNotFoundException("Business not found");
        }
        return metres / 1000.0;
    }

    private Double maxActiveKm(List<DeliveryZone> activeZones) {
        return activeZones.stream().map(z -> z.getMaxDistanceKm().doubleValue()).max(Double::compareTo).orElse(null);
    }

    private DeliveryZone ownedZone(UUID businessId, UUID id) {
        DeliveryZone z = repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Delivery zone not found"));
        if (!z.getBusinessId().equals(businessId)) {
            throw new ResourceNotFoundException("Delivery zone not found");
        }
        return z;
    }

    private void validate(DeliveryZoneRequest req) {
        if (req.minDistanceKm().signum() < 0 || req.maxDistanceKm().compareTo(req.minDistanceKm()) <= 0) {
            throw new BadRequestException("Zone max distance must be greater than min distance.");
        }
        if (req.maxDistanceKm().compareTo(MAX_KM) > 0) {
            throw new BadRequestException("Delivery distance can't exceed " + MAX_KM + " km.");
        }
        if (req.deliveryFee().signum() < 0) {
            throw new BadRequestException("Delivery fee can't be negative.");
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
