package com.bdreview.platform.catalog;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.catalog.CatalogRequests.*;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * CRUD + reorder for the Phase 2 category showcase modules (services, team,
 * menu, products). Reads are public; every write is scoped to the listing's
 * owner exactly like {@code gallery.BusinessPhotoService}. Deliberately small:
 * no e-commerce, booking, or inventory behaviour lives here.
 */
@Service
public class CatalogService {

    /** Generous ceiling — a showcase, not a catalogue. */
    private static final int MAX_PER_MODULE = 60;

    private final BusinessRepository businessRepository;
    private final ServiceOfferingRepository serviceRepository;
    private final TeamMemberRepository teamRepository;
    private final MenuItemRepository menuRepository;
    private final FeaturedProductRepository productRepository;

    public CatalogService(BusinessRepository businessRepository,
                          ServiceOfferingRepository serviceRepository,
                          TeamMemberRepository teamRepository,
                          MenuItemRepository menuRepository,
                          FeaturedProductRepository productRepository) {
        this.businessRepository = businessRepository;
        this.serviceRepository = serviceRepository;
        this.teamRepository = teamRepository;
        this.menuRepository = menuRepository;
        this.productRepository = productRepository;
    }

    // ================================================================
    // Detail-response summary — one cheap EXISTS per module
    // ================================================================
    @Transactional(readOnly = true)
    public CategoryModuleFlags moduleFlags(UUID businessId) {
        return new CategoryModuleFlags(
                serviceRepository.existsByBusinessIdAndSection(businessId, ServiceSection.OFFERING),
                serviceRepository.existsByBusinessIdAndSection(businessId, ServiceSection.FACILITY),
                teamRepository.existsByBusinessId(businessId),
                menuRepository.existsByBusinessId(businessId),
                productRepository.existsByBusinessId(businessId));
    }

    // ================================================================
    // Services / gym membership / gym facilities  (business_service)
    // ================================================================
    @Transactional(readOnly = true)
    public List<ServiceOffering> services(UUID businessId, ServiceSection section) {
        return serviceRepository.findByBusinessIdAndSectionOrderBySortOrderAsc(businessId, nonNull(section));
    }

    @Transactional
    public ServiceOffering addService(UUID ownerUserId, UUID businessId, ServiceOfferingRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        ServiceSection section = nonNull(req.section());
        long existing = serviceRepository.countByBusinessIdAndSection(businessId, section);
        if (existing >= MAX_PER_MODULE) {
            throw new BadRequestException("This list is full (max " + MAX_PER_MODULE + ").");
        }
        return serviceRepository.save(ServiceOffering.builder()
                .businessId(businessId)
                .section(section)
                .name(req.name().trim())
                .description(blankToNull(req.description()))
                .priceText(blankToNull(req.priceText()))
                .sortOrder((int) existing)
                .build());
    }

    @Transactional
    public ServiceOffering updateService(UUID ownerUserId, UUID businessId, UUID id, ServiceOfferingRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        ServiceOffering row = ownedRow(serviceRepository.findById(id), businessId, ServiceOffering::getBusinessId);
        row.setName(req.name().trim());
        row.setDescription(blankToNull(req.description()));
        row.setPriceText(blankToNull(req.priceText()));
        if (req.section() != null) {
            row.setSection(req.section());
        }
        return serviceRepository.save(row);
    }

    @Transactional
    public void deleteService(UUID ownerUserId, UUID businessId, UUID id) {
        getOwnedOrThrow(ownerUserId, businessId);
        serviceRepository.deleteByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public List<ServiceOffering> reorderServices(UUID ownerUserId, UUID businessId, ServiceSection section, List<UUID> orderedIds) {
        getOwnedOrThrow(ownerUserId, businessId);
        List<ServiceOffering> rows = serviceRepository.findByBusinessIdAndSectionOrderBySortOrderAsc(businessId, nonNull(section));
        applyReorder(rows, orderedIds, ServiceOffering::getId, ServiceOffering::setSortOrder, serviceRepository);
        return serviceRepository.findByBusinessIdAndSectionOrderBySortOrderAsc(businessId, nonNull(section));
    }

    // ================================================================
    // Team members  (business_team_member)
    // ================================================================
    @Transactional(readOnly = true)
    public List<TeamMember> team(UUID businessId) {
        return teamRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    @Transactional
    public TeamMember addTeamMember(UUID ownerUserId, UUID businessId, TeamMemberRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        long existing = teamRepository.countByBusinessId(businessId);
        if (existing >= MAX_PER_MODULE) {
            throw new BadRequestException("This list is full (max " + MAX_PER_MODULE + ").");
        }
        return teamRepository.save(TeamMember.builder()
                .businessId(businessId)
                .name(req.name().trim())
                .role(blankToNull(req.role()))
                .bio(blankToNull(req.bio()))
                .photoUrl(blankToNull(req.photoUrl()))
                .sortOrder((int) existing)
                .build());
    }

    @Transactional
    public TeamMember updateTeamMember(UUID ownerUserId, UUID businessId, UUID id, TeamMemberRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        TeamMember row = ownedRow(teamRepository.findById(id), businessId, TeamMember::getBusinessId);
        row.setName(req.name().trim());
        row.setRole(blankToNull(req.role()));
        row.setBio(blankToNull(req.bio()));
        row.setPhotoUrl(blankToNull(req.photoUrl()));
        return teamRepository.save(row);
    }

    @Transactional
    public void deleteTeamMember(UUID ownerUserId, UUID businessId, UUID id) {
        getOwnedOrThrow(ownerUserId, businessId);
        teamRepository.deleteByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public List<TeamMember> reorderTeam(UUID ownerUserId, UUID businessId, List<UUID> orderedIds) {
        getOwnedOrThrow(ownerUserId, businessId);
        List<TeamMember> rows = teamRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
        applyReorder(rows, orderedIds, TeamMember::getId, TeamMember::setSortOrder, teamRepository);
        return teamRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    // ================================================================
    // Menu items  (business_menu_item)
    // ================================================================
    @Transactional(readOnly = true)
    public List<MenuItem> menu(UUID businessId) {
        return menuRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    @Transactional
    public MenuItem addMenuItem(UUID ownerUserId, UUID businessId, MenuItemRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        long existing = menuRepository.countByBusinessId(businessId);
        if (existing >= MAX_PER_MODULE) {
            throw new BadRequestException("This menu is full (max " + MAX_PER_MODULE + ").");
        }
        return menuRepository.save(MenuItem.builder()
                .businessId(businessId)
                .name(req.name().trim())
                .description(blankToNull(req.description()))
                .priceText(blankToNull(req.priceText()))
                .photoUrl(blankToNull(req.photoUrl()))
                .menuSection(blankToNull(req.menuSection()))
                .popular(req.popular())
                .sortOrder((int) existing)
                .build());
    }

    @Transactional
    public MenuItem updateMenuItem(UUID ownerUserId, UUID businessId, UUID id, MenuItemRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        MenuItem row = ownedRow(menuRepository.findById(id), businessId, MenuItem::getBusinessId);
        row.setName(req.name().trim());
        row.setDescription(blankToNull(req.description()));
        row.setPriceText(blankToNull(req.priceText()));
        row.setPhotoUrl(blankToNull(req.photoUrl()));
        row.setMenuSection(blankToNull(req.menuSection()));
        row.setPopular(req.popular());
        return menuRepository.save(row);
    }

    @Transactional
    public void deleteMenuItem(UUID ownerUserId, UUID businessId, UUID id) {
        getOwnedOrThrow(ownerUserId, businessId);
        menuRepository.deleteByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public List<MenuItem> reorderMenu(UUID ownerUserId, UUID businessId, List<UUID> orderedIds) {
        getOwnedOrThrow(ownerUserId, businessId);
        List<MenuItem> rows = menuRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
        applyReorder(rows, orderedIds, MenuItem::getId, MenuItem::setSortOrder, menuRepository);
        return menuRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    // ================================================================
    // Featured products  (business_product)
    // ================================================================
    @Transactional(readOnly = true)
    public List<FeaturedProduct> products(UUID businessId) {
        return productRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    @Transactional
    public FeaturedProduct addProduct(UUID ownerUserId, UUID businessId, FeaturedProductRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        long existing = productRepository.countByBusinessId(businessId);
        if (existing >= MAX_PER_MODULE) {
            throw new BadRequestException("This list is full (max " + MAX_PER_MODULE + ").");
        }
        return productRepository.save(FeaturedProduct.builder()
                .businessId(businessId)
                .name(req.name().trim())
                .description(blankToNull(req.description()))
                .priceText(blankToNull(req.priceText()))
                .photoUrl(blankToNull(req.photoUrl()))
                .sortOrder((int) existing)
                .build());
    }

    @Transactional
    public FeaturedProduct updateProduct(UUID ownerUserId, UUID businessId, UUID id, FeaturedProductRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        FeaturedProduct row = ownedRow(productRepository.findById(id), businessId, FeaturedProduct::getBusinessId);
        row.setName(req.name().trim());
        row.setDescription(blankToNull(req.description()));
        row.setPriceText(blankToNull(req.priceText()));
        row.setPhotoUrl(blankToNull(req.photoUrl()));
        return productRepository.save(row);
    }

    @Transactional
    public void deleteProduct(UUID ownerUserId, UUID businessId, UUID id) {
        getOwnedOrThrow(ownerUserId, businessId);
        productRepository.deleteByIdAndBusinessId(id, businessId);
    }

    @Transactional
    public List<FeaturedProduct> reorderProducts(UUID ownerUserId, UUID businessId, List<UUID> orderedIds) {
        getOwnedOrThrow(ownerUserId, businessId);
        List<FeaturedProduct> rows = productRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
        applyReorder(rows, orderedIds, FeaturedProduct::getId, FeaturedProduct::setSortOrder, productRepository);
        return productRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    // ================================================================
    // shared helpers
    // ================================================================

    /** Same rule as gallery reorder: the list must name every current row id exactly once. */
    private <T> void applyReorder(List<T> rows, List<UUID> orderedIds,
                                  Function<T, UUID> idOf, BiConsumer<T, Integer> setOrder,
                                  JpaRepository<T, UUID> repo) {
        Map<UUID, T> byId = new HashMap<>();
        for (T row : rows) {
            byId.put(idOf.apply(row), row);
        }
        if (orderedIds.size() != rows.size() || !byId.keySet().equals(new HashSet<>(orderedIds))) {
            throw new BadRequestException("Reorder list must contain every item id exactly once.");
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            setOrder.accept(byId.get(orderedIds.get(i)), i);
        }
        repo.saveAll(byId.values());
    }

    private Business getOwnedOrThrow(UUID ownerUserId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!business.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }
        return business;
    }

    /** Loads a child row and asserts it belongs to the given (already owner-checked) business. */
    private <T> T ownedRow(java.util.Optional<T> maybe, UUID businessId, Function<T, UUID> businessIdOf) {
        T row = maybe.orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        if (!businessIdOf.apply(row).equals(businessId)) {
            throw new ResourceNotFoundException("Item not found");
        }
        return row;
    }

    private static ServiceSection nonNull(ServiceSection s) {
        return s == null ? ServiceSection.OFFERING : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
