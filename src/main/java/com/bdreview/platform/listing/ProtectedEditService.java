package com.bdreview.platform.listing;

import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.business.Category;
import com.bdreview.platform.business.CategoryRepository;
import com.bdreview.platform.business.City;
import com.bdreview.platform.business.CityRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Protected edits (V65). When the owner of a VERIFIED business changes its name, phone, address
 * (city / area / map pin) or category, the change is parked in {@code business_pending_change}
 * instead of applied — the public listing keeps the verified values until an admin approves it
 * from Catalog → Pending changes. Every other field still updates immediately.
 */
@Service
public class ProtectedEditService {

    /** The protected fields, in display order. */
    public static final List<String> FIELDS = List.of("name", "contactNumber", "categoryId", "cityId", "areaId", "latitude", "longitude");

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final BusinessPendingChangeRepository repository;
    private final BusinessRepository businessRepository;
    private final CategoryRepository categoryRepository;
    private final CityRepository cityRepository;
    private final AreaRepository areaRepository;
    private final AuditLogService auditLogService;
    private final AdminNotifier notifier;

    public ProtectedEditService(BusinessPendingChangeRepository repository, BusinessRepository businessRepository,
                                CategoryRepository categoryRepository, CityRepository cityRepository,
                                AreaRepository areaRepository, AuditLogService auditLogService, AdminNotifier notifier) {
        this.repository = repository;
        this.businessRepository = businessRepository;
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.areaRepository = areaRepository;
        this.auditLogService = auditLogService;
        this.notifier = notifier;
    }

    /**
     * Called by the owner's update. Returns true when the business is verified and a protected
     * field differs — the change is queued (superseding an older pending one) and the caller must
     * NOT apply the protected fields.
     */
    @Transactional
    public boolean holdIfProtected(Business business, UUID requesterUserId, String name, String normalizedPhone,
                                   UUID categoryId, UUID cityId, UUID areaId, double latitude, double longitude) {
        if (!business.isVerified()) {
            return false;
        }
        Map<String, Object> before = snapshot(business);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name == null ? null : name.trim());
        after.put("contactNumber", normalizedPhone);
        after.put("categoryId", categoryId.toString());
        after.put("cityId", cityId.toString());
        after.put("areaId", areaId.toString());
        after.put("latitude", round(latitude));
        after.put("longitude", round(longitude));
        if (changedFields(before, after).isEmpty()) {
            return false;
        }
        for (BusinessPendingChange old : repository.findByBusinessIdAndStatus(business.getId(), BusinessPendingChange.Status.PENDING)) {
            old.setStatus(BusinessPendingChange.Status.SUPERSEDED);
            old.setReviewedAt(Instant.now());
            repository.save(old);
        }
        repository.save(BusinessPendingChange.builder()
                .businessId(business.getId())
                .requestedBy(requesterUserId)
                .beforeJson(before)
                .afterJson(after)
                .build());
        return true;
    }

    public static Map<String, Object> snapshot(Business b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.getName());
        m.put("contactNumber", b.getContactNumber());
        m.put("categoryId", b.getCategory().getId().toString());
        m.put("cityId", b.getCity().getId().toString());
        m.put("areaId", b.getArea().getId().toString());
        m.put("latitude", b.getLocation() == null ? null : round(b.getLocation().getY()));
        m.put("longitude", b.getLocation() == null ? null : round(b.getLocation().getX()));
        return m;
    }

    public static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        return FIELDS.stream().filter(f -> !Objects.equals(str(before.get(f)), str(after.get(f)))).toList();
    }

    // ---------------------------------------------------------------- admin

    public Page<BusinessPendingChange> queue(BusinessPendingChange.Status status, int page) {
        return repository.findByStatusOrderByCreatedAtAsc(
                status == null ? BusinessPendingChange.Status.PENDING : status, PageRequest.of(page, 25));
    }

    public long pendingCount() {
        return repository.countByStatus(BusinessPendingChange.Status.PENDING);
    }

    /** The owner's own view: the change waiting for review, if any. */
    public BusinessPendingChange pendingFor(UUID businessId) {
        return repository.findFirstByBusinessIdAndStatusOrderByCreatedAtDesc(businessId, BusinessPendingChange.Status.PENDING)
                .orElse(null);
    }

    @Transactional
    public void approve(UUID changeId, String reason) {
        String why = VerificationService.requireReason(reason);
        BusinessPendingChange change = pending(changeId);
        Business business = businessRepository.findById(change.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        Map<String, Object> current = snapshot(business);
        Map<String, Object> a = change.getAfterJson();
        Category category = categoryRepository.findById(UUID.fromString(str(a.get("categoryId"))))
                .orElseThrow(() -> new BadRequestException("The requested category no longer exists."));
        City city = cityRepository.findById(UUID.fromString(str(a.get("cityId"))))
                .orElseThrow(() -> new BadRequestException("The requested city no longer exists."));
        Area area = areaRepository.findById(UUID.fromString(str(a.get("areaId"))))
                .orElseThrow(() -> new BadRequestException("The requested area no longer exists."));
        business.setName(str(a.get("name")));
        business.setContactNumber(str(a.get("contactNumber")));
        business.setCategory(category);
        business.setCity(city);
        business.setArea(area);
        business.setLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(
                ((Number) a.get("longitude")).doubleValue(), ((Number) a.get("latitude")).doubleValue())));
        businessRepository.save(business);

        decide(change, BusinessPendingChange.Status.APPROVED, why);
        auditLogService.record("BUSINESS", business.getId(), "PROTECTED_EDIT_APPROVED", why, current, snapshot(business));
        notifier.notify(business.getOwnerUserId(), "Listing changes approved",
                "Your changes to " + business.getName() + " are now live.", "BUSINESS", business.getId());
    }

    @Transactional
    public void reject(UUID changeId, String reason) {
        String why = VerificationService.requireReason(reason);
        BusinessPendingChange change = pending(changeId);
        decide(change, BusinessPendingChange.Status.REJECTED, why);
        Business business = businessRepository.findById(change.getBusinessId()).orElse(null);
        auditLogService.record("BUSINESS", change.getBusinessId(), "PROTECTED_EDIT_REJECTED", why,
                change.getBeforeJson(), change.getAfterJson());
        if (business != null) {
            notifier.notify(business.getOwnerUserId(), "Listing changes not approved",
                    "Your changes to " + business.getName() + " (name, phone, address or category) were not approved: " + why,
                    "BUSINESS", business.getId());
        }
    }

    /** V67: the owner withdraws their own pending change — the listing simply keeps its current values. */
    @Transactional
    public BusinessPendingChange cancelByOwner(UUID ownerUserId, UUID businessId, UUID changeId) {
        BusinessPendingChange change = pending(changeId);
        if (!change.getBusinessId().equals(businessId)) {
            throw new ResourceNotFoundException("Change not found");
        }
        Business business = businessRepository.findById(businessId).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!ownerUserId.equals(business.getOwnerUserId())) {
            throw new com.bdreview.platform.common.ForbiddenException("You do not own this business listing");
        }
        change.setStatus(BusinessPendingChange.Status.CANCELLED);
        change.setReason("Withdrawn by the owner");
        change.setReviewedAt(Instant.now());
        repository.save(change);
        auditLogService.record("BUSINESS", businessId, "PROTECTED_EDIT_CANCELLED", "Withdrawn by the owner",
                change.getBeforeJson(), change.getAfterJson());
        return change;
    }

    // ----------------------------------------------------------------

    private void decide(BusinessPendingChange change, BusinessPendingChange.Status status, String reason) {
        change.setStatus(status);
        change.setReason(reason);
        change.setReviewedBy(CurrentUser.idOrNull());
        change.setReviewedAt(Instant.now());
        repository.save(change);
    }

    private BusinessPendingChange pending(UUID id) {
        BusinessPendingChange c = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Change not found"));
        if (c.getStatus() != BusinessPendingChange.Status.PENDING) {
            throw new BadRequestException("This change was already " + c.getStatus().name().toLowerCase() + ".");
        }
        return c;
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    private static String str(Object o) {
        return o == null ? null : (o instanceof Number n ? String.valueOf(n.doubleValue()) : o.toString());
    }
}
