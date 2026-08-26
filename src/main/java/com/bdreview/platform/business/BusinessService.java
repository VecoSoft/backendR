package com.bdreview.platform.business;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PhoneNumberUtils;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.gallery.BusinessPhoto;
import com.bdreview.platform.gallery.BusinessPhotoRepository;
import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BusinessService {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BusinessRepository businessRepository;
    private final CategoryRepository categoryRepository;
    private final CityRepository cityRepository;
    private final AreaRepository areaRepository;
    private final BusinessAttributeRepository attributeRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final BusinessPhotoRepository businessPhotoRepository;
    private final BusinessReactionRepository businessReactionRepository;
    private final com.bdreview.platform.catalog.CatalogService catalogService;
    private final com.bdreview.platform.updates.BusinessUpdateService businessUpdateService;
    private final BusinessService self;

    public BusinessService(BusinessRepository businessRepository,
                           CategoryRepository categoryRepository,
                           CityRepository cityRepository,
                           AreaRepository areaRepository,
                           BusinessAttributeRepository attributeRepository,
                           UserRepository userRepository,
                           NotificationService notificationService,
                           BusinessPhotoRepository businessPhotoRepository,
                           BusinessReactionRepository businessReactionRepository,
                           com.bdreview.platform.catalog.CatalogService catalogService,
                           com.bdreview.platform.updates.BusinessUpdateService businessUpdateService,
                           @Lazy BusinessService self) {
        this.businessRepository = businessRepository;
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.areaRepository = areaRepository;
        this.attributeRepository = attributeRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.businessPhotoRepository = businessPhotoRepository;
        this.businessReactionRepository = businessReactionRepository;
        this.catalogService = catalogService;
        this.businessUpdateService = businessUpdateService;
        this.self = self;
    }

    /** Only a BUSINESS_OWNER account can list a business (spec update: two-account model, mirrors biz.yelp.com being the only place listings are managed). */
    @Transactional
    public BusinessResponse create(UUID ownerUserId, CreateBusinessRequest request) {
        CurrentUser.requireRole("BUSINESS_OWNER");
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        City city = cityRepository.findById(request.cityId())
                .orElseThrow(() -> new ResourceNotFoundException("City not found"));
        Area area = areaRepository.findById(request.areaId())
                .orElseThrow(() -> new ResourceNotFoundException("Area not found"));

        Set<BusinessAttribute> attributes = request.attributeIds() == null ? new HashSet<>()
                : new HashSet<>(attributeRepository.findByIdIn(request.attributeIds()));

        Business business = Business.builder()
                .ownerUserId(ownerUserId)
                .name(request.name())
                .slug(generateUniqueSlug(request.name()))
                .category(category)
                .city(city)
                .area(area)
                .contactNumber(PhoneNumberUtils.normalize(request.contactNumber()))
                .operatingHours(request.operatingHours())
                .description(request.description())
                .coverPhotoUrl(request.coverPhotoUrl())
                .logoUrl(request.logoUrl())
                .websiteUrl(blankToNull(request.websiteUrl()))
                .whatsappNumber(normalizePhoneOrNull(request.whatsappNumber()))
                .email(blankToNull(request.email()))
                .facebookUrl(blankToNull(request.facebookUrl()))
                .instagramUrl(blankToNull(request.instagramUrl()))
                .location(point(request.latitude(), request.longitude()))
                .priceTier(request.priceTier())
                .attributes(attributes)
                .build();

        Business saved = businessRepository.save(business);
        return BusinessResponse.from(saved, photoUrlsFor(saved, List.of()), isClaimed(saved.getOwnerUserId()));
    }

    @Transactional
    public BusinessResponse update(UUID requesterUserId, UUID businessId, UpdateBusinessRequest request) {
        Business business = getOwnedOrThrow(requesterUserId, businessId);

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        City city = cityRepository.findById(request.cityId())
                .orElseThrow(() -> new ResourceNotFoundException("City not found"));
        Area area = areaRepository.findById(request.areaId())
                .orElseThrow(() -> new ResourceNotFoundException("Area not found"));
        Set<BusinessAttribute> attributes = request.attributeIds() == null ? new HashSet<>()
                : new HashSet<>(attributeRepository.findByIdIn(request.attributeIds()));

        business.setName(request.name());
        business.setCategory(category);
        business.setCity(city);
        business.setArea(area);
        business.setContactNumber(PhoneNumberUtils.normalize(request.contactNumber()));
        business.setOperatingHours(request.operatingHours());
        business.setDescription(request.description());
        business.setCoverPhotoUrl(request.coverPhotoUrl());
        business.setLogoUrl(request.logoUrl());
        business.setWebsiteUrl(blankToNull(request.websiteUrl()));
        business.setWhatsappNumber(normalizePhoneOrNull(request.whatsappNumber()));
        business.setEmail(blankToNull(request.email()));
        business.setFacebookUrl(blankToNull(request.facebookUrl()));
        business.setInstagramUrl(blankToNull(request.instagramUrl()));
        business.setLocation(point(request.latitude(), request.longitude()));
        business.setPriceTier(request.priceTier());
        business.setAttributes(attributes);
        // slug is immutable by design (spec §1) — never regenerated on update.

        Business saved = businessRepository.save(business);
        List<String> galleryUrls = businessPhotoRepository.findByBusinessIdOrderBySortOrderAsc(saved.getId())
                .stream().map(BusinessPhoto::getUrl).toList();
        return BusinessResponse.from(saved, photoUrlsFor(saved, galleryUrls), isClaimed(saved.getOwnerUserId()));
    }

    @Transactional
    public void softDelete(UUID requesterUserId, UUID businessId) {
        getOwnedOrThrow(requesterUserId, businessId);
        businessRepository.softDelete(businessId);
    }

    /**
     * Report workflow: the "next step" for an owner after their listing was flagged (spec:
     * a flag shouldn't be a dead end). Doesn't clear the flag itself — only an admin can do
     * that, from the Businesses admin screen, after actually looking at the situation — this
     * just makes sure every admin gets told the owner is asking for that look.
     */
    @Transactional
    public void requestFlagReview(UUID requesterUserId, UUID businessId) {
        Business business = getOwnedOrThrow(requesterUserId, businessId);
        if (!business.isFlagged()) {
            throw new BadRequestException("This listing is not currently flagged.");
        }
        self.notifyAdminsOfFlagReviewRequest(businessId, business.getName());
    }

    @Async
    public void notifyAdminsOfFlagReviewRequest(UUID businessId, String businessName) {
        List<User> admins = userRepository.findByRole(UserRole.ADMIN, Pageable.unpaged()).getContent();
        for (User admin : admins) {
            notificationService.create(admin.getId(), NotificationType.FLAG_REVIEW_REQUESTED,
                    "Flag review requested",
                    "The owner of \"" + businessName + "\" has requested a review of their flagged listing.",
                    "BUSINESS", businessId, NotificationChannel.IN_APP);
        }
    }

    /**
     * Business-level card reaction (Like/Dislike/Love/Wow) — toggled the same
     * way review.ReviewService#vote toggles a review vote: a second react()
     * with the same type removes it instead of adding it again.
     */
    @Transactional
    public void react(UUID userId, UUID businessId, BusinessReactionType reactionType) {
        businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        if (businessReactionRepository.existsByBusinessIdAndUserIdAndReactionType(businessId, userId, reactionType)) {
            businessReactionRepository.deleteByBusinessIdAndUserIdAndReactionType(businessId, userId, reactionType);
            adjustReactionCount(businessId, reactionType, -1);
        } else {
            businessReactionRepository.save(BusinessReaction.builder()
                    .businessId(businessId)
                    .userId(userId)
                    .reactionType(reactionType)
                    .build());
            adjustReactionCount(businessId, reactionType, 1);
        }
    }

    private void adjustReactionCount(UUID businessId, BusinessReactionType type, int delta) {
        switch (type) {
            case LIKE -> businessRepository.adjustLikeCount(businessId, delta);
            case DISLIKE -> businessRepository.adjustDislikeCount(businessId, delta);
            case LOVE -> businessRepository.adjustLoveCount(businessId, delta);
            case WOW -> businessRepository.adjustWowCount(businessId, delta);
        }
    }

    @Transactional(readOnly = true)
    public BusinessResponse getBySlug(String slug) {
        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Business not found: " + slug));
        List<String> galleryUrls = businessPhotoRepository.findByBusinessIdOrderBySortOrderAsc(business.getId())
                .stream().map(BusinessPhoto::getUrl).toList();
        // Detail view carries the category-module presence flags (one cheap EXISTS
        // per module) plus the has-updates flag so the public page can pick tabs
        // without loading any module or updates rows.
        return BusinessResponse.from(business, photoUrlsFor(business, galleryUrls),
                isClaimed(business.getOwnerUserId()),
                catalogService.moduleFlags(business.getId()),
                businessUpdateService.hasPublished(business.getId()));
    }

    @Transactional(readOnly = true)
    public Page<BusinessResponse> search(UUID categoryId, UUID areaId, String priceTier, Double minRating,
                                         Double lat, Double lng, Double radiusMeters,
                                         String q, String location, String sort,
                                         int page, int size) {
        Pageable pageable = PageRequest.of(page, com.bdreview.platform.common.PageRequestDefaults.clamp(size));
        Page<Business> results = businessRepository.search(categoryId, areaId, priceTier, minRating, lat, lng,
                radiusMeters, q, location, sort, pageable);
        Map<UUID, List<String>> galleryByBusiness = galleryUrlsByBusiness(results.getContent());
        Map<UUID, Boolean> claimedByOwner = claimedByOwner(results.getContent());
        return results.map(b -> BusinessResponse.from(b,
                photoUrlsFor(b, galleryByBusiness.getOrDefault(b.getId(), List.of())),
                claimedByOwner.getOrDefault(b.getOwnerUserId(), true)));
    }

    @Transactional(readOnly = true)
    public List<BusinessResponse> myBusinesses(UUID ownerUserId) {
        List<Business> businesses = businessRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId);
        Map<UUID, List<String>> galleryByBusiness = galleryUrlsByBusiness(businesses);
        boolean claimed = isClaimed(ownerUserId);
        return businesses.stream()
                .map(b -> BusinessResponse.from(b,
                        photoUrlsFor(b, galleryByBusiness.getOrDefault(b.getId(), List.of())), claimed))
                .toList();
    }

    /** §2: free-text "is my business already listed" search, so a near-match can be claimed instead of duplicated. */
    @Transactional(readOnly = true)
    public List<BusinessResponse> searchForClaim(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }
        List<Business> matches = businessRepository.searchForClaim(query.trim());
        Map<UUID, List<String>> galleryByBusiness = galleryUrlsByBusiness(matches);
        Map<UUID, Boolean> claimedByOwner = claimedByOwner(matches);
        return matches.stream()
                .map(b -> BusinessResponse.from(b,
                        photoUrlsFor(b, galleryByBusiness.getOrDefault(b.getId(), List.of())),
                        claimedByOwner.getOrDefault(b.getOwnerUserId(), true)))
                .toList();
    }

    /** Batched gallery lookup grouped by business id — one query instead of one per business. */
    private Map<UUID, List<String>> galleryUrlsByBusiness(List<Business> businesses) {
        List<UUID> ids = businesses.stream().map(Business::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> byBusiness = new HashMap<>();
        for (BusinessPhoto photo : businessPhotoRepository.findByBusinessIdInOrderBySortOrderAsc(ids)) {
            byBusiness.computeIfAbsent(photo.getBusinessId(), k -> new ArrayList<>()).add(photo.getUrl());
        }
        return byBusiness;
    }

    /** A listing is "claimed" once its owner is a real (non-admin) user — mirrors BusinessClaimService#ensureClaimable. */
    private boolean isClaimed(UUID ownerUserId) {
        return userRepository.findById(ownerUserId).map(u -> u.getRole() != UserRole.ADMIN).orElse(true);
    }

    /** Batched version of {@link #isClaimed(UUID)} for list/search results spanning many distinct owners. */
    private Map<UUID, Boolean> claimedByOwner(List<Business> businesses) {
        List<UUID> ownerIds = businesses.stream().map(Business::getOwnerUserId).distinct().toList();
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> unclaimedOwnerIds = new HashSet<>(userRepository.findIdsByIdInAndRole(ownerIds, UserRole.ADMIN));
        Map<UUID, Boolean> result = new HashMap<>();
        for (UUID ownerId : ownerIds) {
            result.put(ownerId, !unclaimedOwnerIds.contains(ownerId));
        }
        return result;
    }

    /** Card carousel source: cover photo first (if set), then gallery photos, de-duplicated. */
    private List<String> photoUrlsFor(Business business, List<String> galleryUrls) {
        Set<String> ordered = new LinkedHashSet<>();
        if (business.getCoverPhotoUrl() != null && !business.getCoverPhotoUrl().isBlank()) {
            ordered.add(business.getCoverPhotoUrl());
        }
        ordered.addAll(galleryUrls);
        return ordered.stream().collect(Collectors.toList());
    }

    private Business getOwnedOrThrow(UUID requesterUserId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!business.getOwnerUserId().equals(requesterUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }
        return business;
    }

    private String generateUniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "business";
        }
        String slug = base;
        while (businessRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            slug = base + "-" + randomSuffix();
        }
        return slug;
    }

    private static String randomSuffix() {
        return Integer.toHexString(RANDOM.nextInt(0xFFFFFF));
    }

    /** Optional "business presence" text fields: an empty submission is stored as NULL so the public page can simply omit it. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** WhatsApp numbers get the same E.164 normalization as the primary contact number; blank stays blank. */
    private static String normalizePhoneOrNull(String raw) {
        String trimmed = blankToNull(raw);
        return trimmed == null ? null : PhoneNumberUtils.normalize(trimmed);
    }

    private static Point point(double latitude, double longitude) {
        Point p = GEOMETRY_FACTORY.createPoint(new org.locationtech.jts.geom.Coordinate(longitude, latitude));
        p.setSRID(4326);
        return p;
    }
}