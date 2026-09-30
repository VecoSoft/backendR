package com.bdreview.platform.promo;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Who may promote what (V58). Only a listing's owner acts for it — the platform has no staff
 * login accounts (business_team_member rows are display-only), so "owner/staff" is the owner.
 * An admin-imposed promotion restriction blocks every promotion write for that business.
 */
@Component
public class PromoAccess {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("Asia/Dhaka"));

    private final BusinessRepository businessRepository;
    private final PromotionRestrictionRepository restrictionRepository;
    private final CommunitySettingsService settingsService;

    public PromoAccess(BusinessRepository businessRepository, PromotionRestrictionRepository restrictionRepository,
                       CommunitySettingsService settingsService) {
        this.businessRepository = businessRepository;
        this.restrictionRepository = restrictionRepository;
        this.settingsService = settingsService;
    }

    public CommunitySettings.Promotions settings() {
        return settingsService.settings().getPromotions();
    }

    /** 404 for a missing listing, 403 for anyone but its owner. */
    public Business requireOwned(UUID userId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (userId == null || !business.getOwnerUserId().equals(userId)) {
            throw new ForbiddenException("You can only promote a business you own.");
        }
        return business;
    }

    /** Owned AND allowed to promote right now. */
    public Business requireCanPromote(UUID userId, UUID businessId) {
        Business business = requireOwned(userId, businessId);
        restrictionRepository.findActive(Instant.now()).stream()
                .filter(r -> r.getBusinessId().equals(businessId))
                .findFirst()
                .ifPresent(r -> {
                    throw new ForbiddenException("Promotion is suspended for this business"
                            + (r.getEndsAt() == null ? "" : " until " + DATE.format(r.getEndsAt())) + ": " + r.getReason());
                });
        return business;
    }

    public boolean isRestricted(UUID businessId) {
        return restrictionRepository.isRestricted(businessId, Instant.now());
    }
}
