package com.bdreview.platform.business;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ConflictException;
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
    private final BrandRepository brandRepository;
    private final BrandService brandService;
    private final CategoryRepository categoryRepository;
    private final CityRepository cityRepository;
    private final AreaRepository areaRepository;
    private final BusinessAttributeRepository attributeRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final BusinessPhotoRepository businessPhotoRepository;
    private final BusinessReactionRepository businessReactionRepository;
    private final BusinessReactionEventRepository businessReactionEventRepository;
    private final BusinessOperatingHoursRepository businessOperatingHoursRepository;
    private final BusinessHoursExceptionRepository businessHoursExceptionRepository;
    private final com.bdreview.platform.catalog.CatalogService catalogService;
    private final com.bdreview.platform.updates.BusinessUpdateService businessUpdateService;
    private final com.bdreview.platform.review.ReviewService reviewService;
    private final com.bdreview.platform.offer.OfferService offerService;
    private final BusinessService self;

    public BusinessService(BusinessRepository businessRepository,
                           BrandRepository brandRepository,
                           BrandService brandService,
                           CategoryRepository categoryRepository,
                           CityRepository cityRepository,
                           AreaRepository areaRepository,
                           BusinessAttributeRepository attributeRepository,
                           UserRepository userRepository,
                           NotificationService notificationService,
                           BusinessPhotoRepository businessPhotoRepository,
                           BusinessReactionRepository businessReactionRepository,
                           BusinessReactionEventRepository businessReactionEventRepository,
                           BusinessOperatingHoursRepository businessOperatingHoursRepository,
                           BusinessHoursExceptionRepository businessHoursExceptionRepository,
                           com.bdreview.platform.catalog.CatalogService catalogService,
                           com.bdreview.platform.updates.BusinessUpdateService businessUpdateService,
                           com.bdreview.platform.review.ReviewService reviewService,
                           com.bdreview.platform.offer.OfferService offerService,
                           @Lazy BusinessService self) {
        this.businessRepository = businessRepository;
        this.brandRepository = brandRepository;
        this.brandService = brandService;
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.areaRepository = areaRepository;
        this.attributeRepository = attributeRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.businessPhotoRepository = businessPhotoRepository;
        this.businessReactionRepository = businessReactionRepository;
        this.businessReactionEventRepository = businessReactionEventRepository;
        this.businessOperatingHoursRepository = businessOperatingHoursRepository;
        this.businessHoursExceptionRepository = businessHoursExceptionRepository;
        this.catalogService = catalogService;
        this.businessUpdateService = businessUpdateService;
        this.reviewService = reviewService;
        this.offerService = offerService;
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

        validateEstablishedYear(request.establishedYear());

        UUID resolvedBrandId = resolveBrandForCreate(ownerUserId, request);

        String normalizedPhone = PhoneNumberUtils.normalize(request.contactNumber());
        // A declared brand (new or existing) is a deliberate "this is intentionally a related
        // listing" signal, already gated by resolveBrandForCreate's ownership check above — a
        // real chain has multiple outlets that legitimately share a head-office phone number and
        // often sit in the same broad admin "area" (areas aren't granular enough to assume two
        // branches 1-2km apart are actually the same physical location). The duplicate guard
        // exists to stop accidental/malicious clones of an UNRELATED listing, which a brand
        // declaration already rules out, so it's skipped entirely for this path.
        if (resolvedBrandId == null) {
            List<Business> duplicates = businessRepository.findLikelyDuplicates(request.name(), area.getId(), normalizedPhone);
            if (!duplicates.isEmpty()) {
                Business match = duplicates.get(0);
                throw new ConflictException("\"" + match.getName() + "\" already looks like a listing for this business in "
                        + area.getName() + ". If this is your business, claim or dispute that listing instead of creating a new one.");
            }
        }

        Business business = Business.builder()
                .ownerUserId(ownerUserId)
                .name(request.name())
                .slug(generateUniqueSlug(request.name()))
                .category(category)
                .city(city)
                .area(area)
                .brandId(resolvedBrandId)
                .contactNumber(normalizedPhone)
                .operatingHours(request.operatingHours())
                .description(request.description())
                .establishedYear(request.establishedYear())
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

        validateEstablishedYear(request.establishedYear());

        business.setName(request.name());
        business.setCategory(category);
        business.setCity(city);
        business.setArea(area);
        business.setContactNumber(PhoneNumberUtils.normalize(request.contactNumber()));
        business.setOperatingHours(request.operatingHours());
        business.setDescription(request.description());
        business.setEstablishedYear(request.establishedYear());
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
        return BusinessResponse.from(saved, photoUrlsFor(saved, galleryUrls), isClaimed(saved.getOwnerUserId()),
                brandSummariesFor(List.of(saved)).get(saved.getId()));
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
            businessReactionEventRepository.save(BusinessReactionEvent.builder()
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

    /**
     * Full-replace, same shape as catalog.StaffScheduleService#replaceSchedule:
     * validate every day, then delete-all + bulk-insert (never a partial patch).
     * closeTime <= openTime (day not closed) is accepted as "crosses midnight",
     * not rejected — only exact equality is invalid (ambiguous vs. closed/24h).
     */
    @Transactional
    public List<OperatingHoursEntry> replaceOperatingHours(UUID ownerUserId, UUID businessId, ReplaceOperatingHoursRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);

        Set<java.time.DayOfWeek> seen = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
        for (OperatingHoursEntryRequest day : req.days()) {
            if (!seen.add(day.dayOfWeek())) {
                throw new BadRequestException("Each day of the week may only appear once.");
            }
            if (!day.closed()) {
                if (day.openTime() == null || day.closeTime() == null) {
                    throw new BadRequestException(day.dayOfWeek() + ": open and close time are required unless closed.");
                }
                if (day.openTime().equals(day.closeTime())) {
                    throw new BadRequestException(day.dayOfWeek() + ": open and close time can't be the same.");
                }
            }
        }

        businessOperatingHoursRepository.deleteByBusinessId(businessId);
        List<BusinessOperatingHours> rows = req.days().stream()
                .map(d -> BusinessOperatingHours.builder()
                        .businessId(businessId)
                        .dayOfWeek(d.dayOfWeek())
                        .closed(d.closed())
                        .openTime(d.closed() ? null : d.openTime())
                        .closeTime(d.closed() ? null : d.closeTime())
                        .build())
                .toList();
        return businessOperatingHoursRepository.saveAll(rows).stream().map(OperatingHoursEntry::from).toList();
    }

    /**
     * Holiday / special-hours overrides — per-item CRUD (not full-replace like the
     * weekly hours above), since this list grows/shrinks over time rather than
     * always holding exactly 7 rows. Same day/time validation shape.
     */
    @Transactional
    public HoursExceptionEntry addHoursException(UUID ownerUserId, UUID businessId, HoursExceptionRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        validateHoursException(req);
        BusinessHoursException saved = businessHoursExceptionRepository.save(BusinessHoursException.builder()
                .businessId(businessId)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .closed(req.closed())
                .openTime(req.closed() ? null : req.openTime())
                .closeTime(req.closed() ? null : req.closeTime())
                .reason(blankToNull(req.reason()))
                .build());
        return HoursExceptionEntry.from(saved);
    }

    @Transactional
    public HoursExceptionEntry updateHoursException(UUID ownerUserId, UUID businessId, UUID id, HoursExceptionRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        validateHoursException(req);
        BusinessHoursException row = businessHoursExceptionRepository.findById(id)
                .filter(e -> e.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Hours exception not found"));
        row.setStartDate(req.startDate());
        row.setEndDate(req.endDate());
        row.setClosed(req.closed());
        row.setOpenTime(req.closed() ? null : req.openTime());
        row.setCloseTime(req.closed() ? null : req.closeTime());
        row.setReason(blankToNull(req.reason()));
        return HoursExceptionEntry.from(businessHoursExceptionRepository.save(row));
    }

    @Transactional
    public void deleteHoursException(UUID ownerUserId, UUID businessId, UUID id) {
        getOwnedOrThrow(ownerUserId, businessId);
        businessHoursExceptionRepository.deleteByIdAndBusinessId(id, businessId);
    }

    private void validateHoursException(HoursExceptionRequest req) {
        if (req.endDate().isBefore(req.startDate())) {
            throw new BadRequestException("End date must be on or after the start date.");
        }
        if (!req.closed()) {
            if (req.openTime() == null || req.closeTime() == null) {
                throw new BadRequestException("Open and close time are required unless closed.");
            }
            if (req.openTime().equals(req.closeTime())) {
                throw new BadRequestException("Open and close time can't be the same.");
            }
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
        List<OperatingHoursEntry> structuredHours = businessOperatingHoursRepository.findByBusinessId(business.getId())
                .stream().map(OperatingHoursEntry::from).toList();
        List<HoursExceptionEntry> hoursExceptions = businessHoursExceptionRepository.findByBusinessIdOrderByStartDateAsc(business.getId())
                .stream().map(HoursExceptionEntry::from).toList();
        return BusinessResponse.from(business, photoUrlsFor(business, galleryUrls),
                isClaimed(business.getOwnerUserId()),
                brandSummariesFor(List.of(business)).get(business.getId()),
                catalogService.moduleFlags(business.getId()),
                businessUpdateService.hasPublished(business.getId()),
                catalogService.hasFaq(business.getId()),
                structuredHours, hoursExceptions);
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
        Map<UUID, BrandSummary> brandByBusiness = brandSummariesFor(results.getContent());
        // Hours (for "open now"), a top review snippet, and the current active offer — each one
        // batched query for the whole page, same convention as gallery/claimed/brand above, not
        // a per-row lookup. See BusinessResponse's search()-only from() overload.
        Map<UUID, List<OperatingHoursEntry>> hoursByBusiness = structuredHoursByBusiness(results.getContent());
        Map<UUID, List<HoursExceptionEntry>> exceptionsByBusiness = hoursExceptionsByBusiness(results.getContent());
        List<UUID> businessIds = results.getContent().stream().map(Business::getId).toList();
        Map<UUID, String> snippetByBusiness = reviewService.topReviewSnippetsByBusiness(businessIds);
        Map<UUID, com.bdreview.platform.offer.ActiveOfferSummary> offerByBusiness =
                offerService.activeOfferSummariesByBusiness(businessIds);
        List<BusinessResponse> mapped = results.getContent().stream()
                .map(b -> BusinessResponse.from(b,
                        photoUrlsFor(b, galleryByBusiness.getOrDefault(b.getId(), List.of())),
                        claimedByOwner.getOrDefault(b.getOwnerUserId(), true),
                        brandByBusiness.get(b.getId()),
                        hoursByBusiness.getOrDefault(b.getId(), List.of()),
                        exceptionsByBusiness.getOrDefault(b.getId(), List.of()),
                        snippetByBusiness.get(b.getId()),
                        offerByBusiness.get(b.getId())))
                .toList();
        // Collapse rows sharing a brand into one card ("KFC — 5 branches" instead of N cards).
        // branchCount on the surviving row is still the TRUE total across all live branches (from
        // the batched brandSummariesFor lookup above), not just how many happen to be on this page.
        // Known limitation: totalElements/totalPages below still reflect raw row counts, so a page
        // can render fewer than `size` cards when it contains multiple branches of one brand.
        return new org.springframework.data.domain.PageImpl<>(
                collapseBrandDuplicates(mapped), pageable, results.getTotalElements());
    }

    private List<BusinessResponse> collapseBrandDuplicates(List<BusinessResponse> content) {
        List<BusinessResponse> out = new ArrayList<>();
        Set<UUID> seenBrandIds = new HashSet<>();
        for (BusinessResponse b : content) {
            if (b.brandId() == null || seenBrandIds.add(b.brandId())) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * Used by the owner edit workspace (BusinessForm), which needs the structured
     * weekly hours to hydrate the OperatingHoursPicker on edit — unlike search()'s
     * lean 3-arg from(), this passes structuredHours through (still bundled via
     * a batched query, not N+1, since findByOwnerUserIdAndDeletedAtIsNull rarely
     * returns more than a handful of rows per owner).
     */
    @Transactional(readOnly = true)
    public List<BusinessResponse> myBusinesses(UUID ownerUserId) {
        List<Business> businesses = businessRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId);
        Map<UUID, List<String>> galleryByBusiness = galleryUrlsByBusiness(businesses);
        Map<UUID, List<OperatingHoursEntry>> hoursByBusiness = structuredHoursByBusiness(businesses);
        Map<UUID, List<HoursExceptionEntry>> exceptionsByBusiness = hoursExceptionsByBusiness(businesses);
        Map<UUID, BrandSummary> brandByBusiness = brandSummariesFor(businesses);
        boolean claimed = isClaimed(ownerUserId);
        return businesses.stream()
                .map(b -> BusinessResponse.from(b,
                        photoUrlsFor(b, galleryByBusiness.getOrDefault(b.getId(), List.of())), claimed,
                        brandByBusiness.get(b.getId()),
                        null, null, null, hoursByBusiness.getOrDefault(b.getId(), List.of()),
                        exceptionsByBusiness.getOrDefault(b.getId(), List.of())))
                .toList();
    }

    /** Every live branch of a brand — GET /api/v1/brands/{slug}/branches. */
    @Transactional(readOnly = true)
    public List<BusinessResponse> businessesForBrand(String brandSlug) {
        Brand brand = brandRepository.findBySlugAndDeletedAtIsNull(brandSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found: " + brandSlug));
        List<Business> businesses = businessRepository.findByBrandIdAndDeletedAtIsNull(brand.getId());
        Map<UUID, List<String>> galleryByBusiness = galleryUrlsByBusiness(businesses);
        Map<UUID, Boolean> claimedByOwner = claimedByOwner(businesses);
        Map<UUID, BrandSummary> brandByBusiness = brandSummariesFor(businesses);
        return businesses.stream()
                .map(b -> BusinessResponse.from(b,
                        photoUrlsFor(b, galleryByBusiness.getOrDefault(b.getId(), List.of())),
                        claimedByOwner.getOrDefault(b.getOwnerUserId(), true),
                        brandByBusiness.get(b.getId())))
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
        Map<UUID, BrandSummary> brandByBusiness = brandSummariesFor(matches);
        return matches.stream()
                .map(b -> BusinessResponse.from(b,
                        photoUrlsFor(b, galleryByBusiness.getOrDefault(b.getId(), List.of())),
                        claimedByOwner.getOrDefault(b.getOwnerUserId(), true),
                        brandByBusiness.get(b.getId())))
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

    private Map<UUID, List<OperatingHoursEntry>> structuredHoursByBusiness(List<Business> businesses) {
        List<UUID> ids = businesses.stream().map(Business::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<OperatingHoursEntry>> byBusiness = new HashMap<>();
        for (BusinessOperatingHours h : businessOperatingHoursRepository.findByBusinessIdIn(ids)) {
            byBusiness.computeIfAbsent(h.getBusinessId(), k -> new ArrayList<>()).add(OperatingHoursEntry.from(h));
        }
        return byBusiness;
    }

    private Map<UUID, List<HoursExceptionEntry>> hoursExceptionsByBusiness(List<Business> businesses) {
        List<UUID> ids = businesses.stream().map(Business::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<HoursExceptionEntry>> byBusiness = new HashMap<>();
        for (BusinessHoursException e : businessHoursExceptionRepository.findByBusinessIdIn(ids)) {
            byBusiness.computeIfAbsent(e.getBusinessId(), k -> new ArrayList<>()).add(HoursExceptionEntry.from(e));
        }
        return byBusiness;
    }

    /**
     * Batched brand lookup grouped by business id — one round trip for every distinct
     * brand_id present, same convention as galleryUrlsByBusiness/claimedByOwner. Rating
     * is rolled up on read (SUM(rating_sum)/SUM(review_count)), rounded the same way as
     * BusinessRepository#applyRatingAggregateDelta — see Business#brandId's javadoc for
     * why this isn't a mirrored atomic delta instead.
     */
    private Map<UUID, BrandSummary> brandSummariesFor(List<Business> businesses) {
        List<UUID> brandIds = businesses.stream().map(Business::getBrandId).filter(java.util.Objects::nonNull).distinct().toList();
        if (brandIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Brand> brandsById = brandRepository.findByIdInAndDeletedAtIsNull(brandIds).stream()
                .collect(Collectors.toMap(Brand::getId, b -> b));
        Map<UUID, com.bdreview.platform.business.BrandAggregateRow> aggregateByBrand = brandRepository.aggregatesFor(brandIds).stream()
                .collect(Collectors.toMap(BrandAggregateRow::getBrandId, row -> row));
        Map<UUID, BrandSummary> byBusiness = new HashMap<>();
        for (Business b : businesses) {
            if (b.getBrandId() == null) continue;
            Brand brand = brandsById.get(b.getBrandId());
            if (brand == null) continue; // brand soft-deleted since being linked — treat this listing as unbranded
            BrandAggregateRow row = aggregateByBrand.get(brand.getId());
            int branchCount = row != null ? (int) row.getBranchCount() : 0;
            java.math.BigDecimal avgRating = row == null || row.getReviewCount() <= 0 ? java.math.BigDecimal.ZERO
                    : java.math.BigDecimal.valueOf(row.getRatingSum())
                            .divide(java.math.BigDecimal.valueOf(row.getReviewCount()), 2, java.math.RoundingMode.HALF_UP);
            byBusiness.put(b.getId(), new BrandSummary(brand.getId(), brand.getName(), brand.getSlug(), branchCount, avgRating));
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

    /**
     * Brand → Branches linking at create time. Declaring a brand-new chain (newBrandName) is
     * always self-service — it's just a label on the owner's own listing, zero risk. Linking to
     * an EXISTING brand (brandId) is self-service only when the owner already owns another
     * business under that brand; the true cross-owner franchise case (a different account's
     * listing joining a chain) needs an admin, via the admin business-edit screen, to prevent
     * brand squatting.
     */
    private UUID resolveBrandForCreate(UUID ownerUserId, CreateBusinessRequest request) {
        boolean hasNewName = request.newBrandName() != null && !request.newBrandName().isBlank();
        if (hasNewName && request.brandId() != null) {
            throw new BadRequestException("Provide either an existing brand or a new brand name, not both.");
        }
        if (hasNewName) {
            return brandService.createOrReuseByName(request.newBrandName()).getId();
        }
        if (request.brandId() == null) {
            return null;
        }
        if (!brandRepository.existsById(request.brandId())) {
            throw new ResourceNotFoundException("Brand not found");
        }
        boolean ownerAlreadyOnThisBrand = businessRepository.findByOwnerUserIdAndDeletedAtIsNull(ownerUserId).stream()
                .anyMatch(b -> request.brandId().equals(b.getBrandId()));
        if (!ownerAlreadyOnThisBrand) {
            throw new ForbiddenException(
                    "Linking to a brand you don't already have a listing under needs admin approval — contact support.");
        }
        return request.brandId();
    }

    /** Upper bound is dynamic (current year), so this is manual validation rather than a bean-validation annotation. */
    private void validateEstablishedYear(Integer establishedYear) {
        if (establishedYear == null) return;
        int currentYear = java.time.Year.now().getValue();
        if (establishedYear < 1900 || establishedYear > currentYear) {
            throw new BadRequestException("Established year must be between 1900 and " + currentYear + ".");
        }
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