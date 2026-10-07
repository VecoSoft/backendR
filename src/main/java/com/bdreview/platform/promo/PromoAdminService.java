package com.bdreview.platform.promo;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.CommunityPost;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.community.moderation.CommunityModerationService;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.promo.PromoEnums.BusinessPostStatus;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin side of business promotion (V58). ADMIN: settings, templates, boost packages, promotion
 * restrictions and revenue. MODERATOR (or ADMIN): the business-post review queue. Boost payment
 * and review actions live in {@link BoostService}. Every write is audit-logged with before/after.
 */
@Service
public class PromoAdminService {

    public record PackageRequest(@NotBlank @Size(max = 80) String name, @NotNull @DecimalMin("1") @DecimalMax("1000000") BigDecimal priceBdt,
                                 @Min(1) @Max(10_000_000) int estImpressions, @Min(1) @Max(90) int durationDays,
                                 @Min(1) @Max(50) int maxRadiusKm, boolean active, int sortOrder) {
    }

    public record RestrictionRequest(@NotNull UUID businessId, /** null = until lifted */ Integer days, @NotBlank @Size(max = 500) String reason) {
    }

    public record PendingPost(UUID postId, UUID businessId, String businessName, String type, String title, String body,
                              UUID offerId, UUID creativeId, String squareUrl, Instant createdAt) {
    }

    public record RevenueRow(LocalDate day, String packageName, long boosts, BigDecimal amount) {
    }

    public record RestrictionView(UUID id, UUID businessId, String businessName, String reason, Instant endsAt, Instant createdAt) {
    }

    private final CommunitySettingsService settingsService;
    private final PromoTemplateRepository templateRepository;
    private final BoostPackageRepository packageRepository;
    private final PromotionRestrictionRepository restrictionRepository;
    private final BusinessPostRepository businessPostRepository;
    private final CommunityPostRepository communityPostRepository;
    private final PromoCreativeRepository creativeRepository;
    private final BusinessRepository businessRepository;
    private final BoostRepository boostRepository;
    private final AuditLogService auditLogService;

    public PromoAdminService(CommunitySettingsService settingsService, PromoTemplateRepository templateRepository,
                             BoostPackageRepository packageRepository, PromotionRestrictionRepository restrictionRepository,
                             BusinessPostRepository businessPostRepository, CommunityPostRepository communityPostRepository,
                             PromoCreativeRepository creativeRepository, BusinessRepository businessRepository,
                             BoostRepository boostRepository, AuditLogService auditLogService) {
        this.settingsService = settingsService;
        this.templateRepository = templateRepository;
        this.packageRepository = packageRepository;
        this.restrictionRepository = restrictionRepository;
        this.businessPostRepository = businessPostRepository;
        this.communityPostRepository = communityPostRepository;
        this.creativeRepository = creativeRepository;
        this.businessRepository = businessRepository;
        this.boostRepository = boostRepository;
        this.auditLogService = auditLogService;
    }

    // ---- Settings (ADMIN) — saved through the existing audited community settings path ----

    public CommunitySettings.Promotions settings() {
        CommunityModerationService.requireStaff();
        return settingsService.loadStoredSettings().getPromotions();
    }

    @Transactional
    public CommunitySettings.Promotions saveSettings(CommunitySettings.Promotions promotions, String reason) {
        CurrentUser.requireRole("ADMIN");
        CommunitySettings all = settingsService.loadStoredSettings();
        all.setPromotions(promotions);
        return settingsService.save(all, reason == null || reason.isBlank() ? "Promotion settings updated" : reason).getPromotions();
    }

    // ---- Templates (ADMIN) ----

    public List<PromoTemplate> templates() {
        CommunityModerationService.requireStaff();
        return templateRepository.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional
    public void setTemplateActive(String key, boolean active) {
        CurrentUser.requireRole("ADMIN");
        PromoTemplate t = templateRepository.findByKey(key).orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        if (!active && templateRepository.findByActiveTrueOrderBySortOrderAscNameAsc().size() <= 1 && t.isActive()) {
            throw new BadRequestException("At least one template must stay enabled.");
        }
        boolean before = t.isActive();
        t.setActive(active);
        templateRepository.save(t);
        auditLogService.record("PROMO_TEMPLATE", t.getId(), active ? "TEMPLATE_ENABLED" : "TEMPLATE_DISABLED", key,
                Map.of("active", before), Map.of("active", active));
    }

    /** New order = the given keys first, in order; anything not listed keeps its relative order after them. */
    @Transactional
    public void reorderTemplates(List<String> keys) {
        CurrentUser.requireRole("ADMIN");
        List<PromoTemplate> all = templateRepository.findAllByOrderBySortOrderAscNameAsc();
        Map<String, Integer> before = all.stream().collect(Collectors.toMap(PromoTemplate::getKey, PromoTemplate::getSortOrder));
        List<PromoTemplate> ordered = new ArrayList<>();
        for (String k : keys) {
            all.stream().filter(t -> t.getKey().equals(k)).findFirst().ifPresent(ordered::add);
        }
        all.stream().filter(t -> !ordered.contains(t)).forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setSortOrder((i + 1) * 10);
        }
        templateRepository.saveAll(ordered);
        auditLogService.record("PROMO_TEMPLATE", null, "TEMPLATES_REORDERED", String.join(", ", keys), before,
                ordered.stream().collect(Collectors.toMap(PromoTemplate::getKey, PromoTemplate::getSortOrder)));
    }

    // ---- Boost packages (ADMIN) ----

    public List<BoostPackage> packages() {
        CommunityModerationService.requireStaff();
        return packageRepository.findAllByOrderBySortOrderAscPriceBdtAsc();
    }

    @Transactional
    public BoostPackage savePackage(UUID id, PackageRequest req) {
        CurrentUser.requireRole("ADMIN");
        BoostPackage p = id == null ? new BoostPackage()
                : packageRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Package not found"));
        Map<String, Object> before = id == null ? null : packageSnapshot(p);
        p.setName(req.name().strip());
        p.setPriceBdt(req.priceBdt());
        p.setEstImpressions(req.estImpressions());
        p.setDurationDays(req.durationDays());
        p.setMaxRadiusKm(req.maxRadiusKm());
        p.setActive(req.active());
        p.setSortOrder(req.sortOrder());
        p = packageRepository.save(p);
        auditLogService.record("BOOST_PACKAGE", p.getId(), id == null ? "PACKAGE_CREATED" : "PACKAGE_UPDATED", p.getName(),
                before, packageSnapshot(p));
        return p;
    }

    @Transactional
    public void deletePackage(UUID id) {
        CurrentUser.requireRole("ADMIN");
        BoostPackage p = packageRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Package not found"));
        // Boosts snapshot their package; still, keep bought packages around (deactivate) rather than orphan them.
        boolean used = boostRepository.findAll().stream().anyMatch(b -> b.getPackageId().equals(id));
        Map<String, Object> before = packageSnapshot(p);
        if (used) {
            p.setActive(false);
            packageRepository.save(p);
            auditLogService.record("BOOST_PACKAGE", id, "PACKAGE_DEACTIVATED", "Package has boosts — deactivated instead of deleted",
                    before, packageSnapshot(p));
        } else {
            packageRepository.delete(p);
            auditLogService.record("BOOST_PACKAGE", id, "PACKAGE_DELETED", p.getName(), before, null);
        }
    }

    private static Map<String, Object> packageSnapshot(BoostPackage p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("priceBdt", p.getPriceBdt());
        m.put("estImpressions", p.getEstImpressions());
        m.put("durationDays", p.getDurationDays());
        m.put("maxRadiusKm", p.getMaxRadiusKm());
        m.put("active", p.isActive());
        return m;
    }

    // ---- Business post review queue (MODERATOR / ADMIN) ----

    @Transactional(readOnly = true)
    public List<PendingPost> pendingPosts() {
        CommunityModerationService.requireStaff();
        List<BusinessPost> rows = businessPostRepository.findByStatusOrderByCreatedAtAsc(BusinessPostStatus.PENDING_REVIEW);
        Map<UUID, CommunityPost> posts = communityPostRepository.findAllById(rows.stream().map(BusinessPost::getPostId).toList())
                .stream().collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        Map<UUID, Business> businesses = businessRepository.findAllById(rows.stream().map(BusinessPost::getBusinessId).distinct().toList())
                .stream().collect(Collectors.toMap(Business::getId, Function.identity()));
        Map<UUID, PromoCreative> creatives = creativeRepository.findAllById(rows.stream().map(BusinessPost::getCreativeId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(PromoCreative::getId, Function.identity()));
        List<PendingPost> out = new ArrayList<>();
        for (BusinessPost bp : rows) {
            CommunityPost p = posts.get(bp.getPostId());
            if (p == null || p.getDeletedAt() != null) {
                continue;
            }
            Business b = businesses.get(bp.getBusinessId());
            PromoCreative c = bp.getCreativeId() == null ? null : creatives.get(bp.getCreativeId());
            out.add(new PendingPost(bp.getPostId(), bp.getBusinessId(), b == null ? "?" : b.getName(), bp.getType().name(),
                    p.getTitle(), p.getBody(), bp.getOfferId(), bp.getCreativeId(), c == null ? null : c.getSquareUrl(), bp.getCreatedAt()));
        }
        return out;
    }

    // ---- Promotion restrictions (ADMIN) ----

    @Transactional
    public PromotionRestriction restrict(RestrictionRequest req) {
        CurrentUser.requireRole("ADMIN");
        Business b = businessRepository.findById(req.businessId()).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (req.days() != null && (req.days() < 1 || req.days() > 3650)) {
            throw new BadRequestException("Duration must be 1–3650 days, or empty for indefinitely.");
        }
        PromotionRestriction r = restrictionRepository.save(PromotionRestriction.builder()
                .businessId(b.getId())
                .reason(req.reason().strip())
                .endsAt(req.days() == null ? null : Instant.now().plus(Duration.ofDays(req.days())))
                .createdBy(CurrentUser.id())
                .build());
        auditLogService.record("BUSINESS", b.getId(), "PROMOTION_SUSPENDED", req.reason().strip(), null,
                Map.of("endsAt", String.valueOf(r.getEndsAt()), "business", b.getName()));
        return r;
    }

    @Transactional
    public void lift(UUID restrictionId, String reason) {
        CurrentUser.requireRole("ADMIN");
        PromotionRestriction r = restrictionRepository.findById(restrictionId)
                .orElseThrow(() -> new ResourceNotFoundException("Restriction not found"));
        r.setLiftedAt(Instant.now());
        restrictionRepository.save(r);
        auditLogService.record("BUSINESS", r.getBusinessId(), "PROMOTION_RESTORED",
                reason == null || reason.isBlank() ? "Promotion rights restored" : reason.strip(),
                Map.of("endsAt", String.valueOf(r.getEndsAt())), Map.of("liftedAt", r.getLiftedAt().toString()));
    }

    @Transactional(readOnly = true)
    public List<RestrictionView> activeRestrictions() {
        CommunityModerationService.requireStaff();
        List<PromotionRestriction> rows = restrictionRepository.findActive(Instant.now());
        Map<UUID, String> names = businessRepository.findAllById(rows.stream().map(PromotionRestriction::getBusinessId).distinct().toList())
                .stream().collect(Collectors.toMap(Business::getId, Business::getName));
        return rows.stream().map(r -> new RestrictionView(r.getId(), r.getBusinessId(), names.getOrDefault(r.getBusinessId(), "?"),
                r.getReason(), r.getEndsAt(), r.getCreatedAt())).toList();
    }

    // ---- Tags for the community reports queue ----

    /** Post id → "SPONSORED" (has a live or paused boost) or "BUSINESS" (a business post). Others are absent. */
    public Map<UUID, String> promotionTags(Collection<UUID> postIds) {
        List<UUID> ids = postIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        businessPostRepository.findByPostIdIn(ids).forEach(bp -> out.put(bp.getPostId(), "BUSINESS"));
        for (UUID id : out.keySet().stream().toList()) {
            if (boostRepository.existsByPostIdAndStatusIn(id,
                    EnumSet.of(PromoEnums.BoostStatus.ACTIVE, PromoEnums.BoostStatus.PAUSED))) {
                out.put(id, "SPONSORED");
            }
        }
        return out;
    }

    // ---- Revenue (ADMIN) ----

    public List<RevenueRow> revenue(int days) {
        CurrentUser.requireAuthority("PERM_FINANCE"); // SUPER_ADMIN or FINANCE (V67)
        Instant since = Instant.now().minus(Duration.ofDays(Math.min(Math.max(days, 1), 366)));
        return boostRepository.revenueSince(since).stream().map(r -> new RevenueRow(
                r[0] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) r[0],
                (String) r[1], ((Number) r[2]).longValue(), (BigDecimal) r[3])).toList();
    }
}
