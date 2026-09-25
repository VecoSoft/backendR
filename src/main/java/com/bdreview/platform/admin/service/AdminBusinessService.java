package com.bdreview.platform.admin.service;

import com.bdreview.platform.admin.form.BusinessForm;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.business.*;
import com.bdreview.platform.common.PhoneNumberUtils;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-panel counterpart of {@code business.BusinessService}. The original
 * service is ownership-scoped (an owner can only touch their own listing);
 * admins need to create/edit/verify/soft-delete/restore ANY listing, so
 * rather than weaken the existing owner checks, this is a small, separate
 * service with the same field-mapping and slug/point conventions.
 */
@Service
public class AdminBusinessService {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BusinessRepository businessRepository;
    private final CategoryRepository categoryRepository;
    private final CityRepository cityRepository;
    private final AreaRepository areaRepository;
    private final BusinessAttributeRepository attributeRepository;
    private final UserRepository userRepository;

    public AdminBusinessService(BusinessRepository businessRepository,
                                 CategoryRepository categoryRepository,
                                 CityRepository cityRepository,
                                 AreaRepository areaRepository,
                                 BusinessAttributeRepository attributeRepository,
                                 UserRepository userRepository) {
        this.businessRepository = businessRepository;
        this.categoryRepository = categoryRepository;
        this.cityRepository = cityRepository;
        this.areaRepository = areaRepository;
        this.attributeRepository = attributeRepository;
        this.userRepository = userRepository;
    }

    public Page<Business> search(String query, UUID categoryId, UUID cityId, boolean includeDeleted,
                                  boolean unclaimedOnly, int page) {
        Pageable pageable = PageRequest.of(page, 20);
        return businessRepository.adminSearch(blankToNull(query), categoryId, cityId, includeDeleted, unclaimedOnly, pageable);
    }

    /** Which of a result page's owners are still admin-placeholders — drives the "Unclaimed" badge in businesses/list.html. */
    public Set<UUID> unclaimedOwnerIds(List<Business> businesses) {
        List<UUID> ownerIds = businesses.stream().map(Business::getOwnerUserId).distinct().toList();
        if (ownerIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(userRepository.findIdsByIdInAndRole(ownerIds, UserRole.ADMIN));
    }

    public Business get(UUID id) {
        return businessRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    /**
     * Admin-seeded listing (spec §2: "admin-seeded or already-listed
     * business"). The admin becomes the placeholder owner until a real owner
     * claims it through the existing claim-approval flow, which already
     * reassigns {@code ownerUserId}.
     */
    @Transactional
    public Business create(UUID adminId, BusinessForm form) {
        validate(form);
        Business business = Business.builder()
                .ownerUserId(adminId)
                .name(form.getName())
                .slug(generateUniqueSlug(form.getName()))
                .category(requireCategory(form.getCategoryId()))
                .city(requireCity(form.getCityId()))
                .area(requireArea(form.getAreaId()))
                .contactNumber(PhoneNumberUtils.normalize(form.getContactNumber()))
                .operatingHours(form.getOperatingHours())
                .description(form.getDescription())
                .coverPhotoUrl(form.getCoverPhotoUrl())
                .location(point(form.getLatitude(), form.getLongitude()))
                .priceTier(form.getPriceTier())
                .attributes(resolveAttributes(form.getAttributeIds()))
                .verified(form.isVerified())
                .brandId(form.getBrandId())
                .build();
        return businessRepository.save(business);
    }

    @Transactional
    public Business update(UUID id, BusinessForm form) {
        validate(form);
        Business business = get(id);
        business.setName(form.getName());
        business.setCategory(requireCategory(form.getCategoryId()));
        business.setCity(requireCity(form.getCityId()));
        business.setArea(requireArea(form.getAreaId()));
        business.setContactNumber(PhoneNumberUtils.normalize(form.getContactNumber()));
        business.setOperatingHours(form.getOperatingHours());
        business.setDescription(form.getDescription());
        business.setCoverPhotoUrl(form.getCoverPhotoUrl());
        business.setLocation(point(form.getLatitude(), form.getLongitude()));
        business.setPriceTier(form.getPriceTier());
        business.setAttributes(resolveAttributes(form.getAttributeIds()));
        business.setVerified(form.isVerified());
        business.setBrandId(form.getBrandId());
        // slug stays immutable, exactly as in business.BusinessService#update
        return businessRepository.save(business);
    }

    @Transactional
    public void softDelete(UUID id) {
        businessRepository.softDelete(id);
    }

    @Transactional
    public void restore(UUID id) {
        businessRepository.restore(id);
    }

    @Transactional
    public void setVerified(UUID id, boolean verified) {
        businessRepository.setVerified(id, verified);
    }

    /** Clears a report-workflow flag once an admin has reviewed the situation (report package). */
    @Transactional
    public void unflag(UUID id) {
        businessRepository.unflag(id);
    }

    private Category requireCategory(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    private City requireCity(UUID id) {
        return cityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("City not found"));
    }

    private Area requireArea(UUID id) {
        return areaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Area not found"));
    }

    private Set<BusinessAttribute> resolveAttributes(java.util.List<UUID> ids) {
        return ids == null || ids.isEmpty() ? new HashSet<>() : new HashSet<>(attributeRepository.findByIdIn(ids));
    }

    private void validate(BusinessForm form) {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new com.bdreview.platform.common.BadRequestException("Business name is required.");
        }
        if (form.getCategoryId() == null || form.getCityId() == null || form.getAreaId() == null) {
            throw new com.bdreview.platform.common.BadRequestException("Category, city and area are all required.");
        }
        if (form.getContactNumber() == null || form.getContactNumber().isBlank()) {
            throw new com.bdreview.platform.common.BadRequestException("Contact number is required.");
        }
        if (form.getLatitude() == null || form.getLongitude() == null) {
            throw new com.bdreview.platform.common.BadRequestException("Latitude and longitude are required.");
        }
        if (form.getPriceTier() == null) {
            throw new com.bdreview.platform.common.BadRequestException("Price tier is required.");
        }
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
            slug = base + "-" + Integer.toHexString(RANDOM.nextInt(0xFFFFFF));
        }
        return slug;
    }

    private static Point point(double latitude, double longitude) {
        Point p = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
        p.setSRID(4326);
        return p;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
