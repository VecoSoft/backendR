package com.bdreview.platform.completeness;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.business.CategoryKind;
import com.bdreview.platform.catalog.FeaturedProductRepository;
import com.bdreview.platform.catalog.MenuItemRepository;
import com.bdreview.platform.catalog.ServiceOfferingRepository;
import com.bdreview.platform.catalog.ServiceSection;
import com.bdreview.platform.catalog.TeamMemberRepository;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.completeness.CompletenessResponse.Item;
import com.bdreview.platform.gallery.BusinessPhotoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Single, centralised profile-completeness calculation (Phase 3). One ordered
 * checklist; each applicable item is worth an equal share of 100%. Category
 * modules are added to the checklist only for the kinds they belong to.
 */
@Service
public class ProfileCompletenessService {

    private final BusinessRepository businessRepository;
    private final BusinessPhotoRepository photoRepository;
    private final ServiceOfferingRepository serviceRepository;
    private final TeamMemberRepository teamRepository;
    private final MenuItemRepository menuRepository;
    private final FeaturedProductRepository productRepository;

    public ProfileCompletenessService(BusinessRepository businessRepository,
                                      BusinessPhotoRepository photoRepository,
                                      ServiceOfferingRepository serviceRepository,
                                      TeamMemberRepository teamRepository,
                                      MenuItemRepository menuRepository,
                                      FeaturedProductRepository productRepository) {
        this.businessRepository = businessRepository;
        this.photoRepository = photoRepository;
        this.serviceRepository = serviceRepository;
        this.teamRepository = teamRepository;
        this.menuRepository = menuRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public CompletenessResponse forOwner(UUID requesterUserId, boolean requesterIsAdmin, UUID businessId) {
        Business b = businessRepository.findByIdWithDetails(businessId)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!requesterIsAdmin && !b.getOwnerUserId().equals(requesterUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }

        List<Item> completed = new ArrayList<>();
        List<Item> recommended = new ArrayList<>();

        // --- always-applicable items -------------------------------------
        add(completed, recommended, "basics", "Basic information", null, true);
        add(completed, recommended, "location", "Location", null, true);
        add(completed, recommended, "contact", "Contact details", null, true);
        add(completed, recommended, "description", "Business description",
                "Add a short description of your business", notBlank(b.getDescription()));
        add(completed, recommended, "hours", "Operating hours",
                "Add your opening hours", notBlank(b.getOperatingHours()));
        add(completed, recommended, "coverPhoto", "Cover photo",
                "Upload a cover photo", notBlank(b.getCoverPhotoUrl()));
        add(completed, recommended, "logo", "Profile picture",
                "Upload a profile picture or logo", notBlank(b.getLogoUrl()));
        add(completed, recommended, "gallery", "Gallery photos",
                "Add a few gallery photos", photoRepository.countByBusinessId(businessId) > 0);
        add(completed, recommended, "presence", "Website or social link",
                "Add a website, WhatsApp, or social link", hasAnyPresence(b));

        // --- category-specific items (only the ones this kind uses) ------
        for (CategoryItem ci : categoryItems(b.getCategory().getKind())) {
            add(completed, recommended, ci.key, ci.label, ci.action, ci.done(this, businessId));
        }

        int applicable = completed.size() + recommended.size();
        int percentage = applicable == 0 ? 100 : Math.round(completed.size() * 100f / applicable);
        return new CompletenessResponse(percentage, completed, recommended);
    }

    private static void add(List<Item> completed, List<Item> recommended,
                            String key, String label, String action, boolean done) {
        if (done) {
            completed.add(new Item(key, label, null));
        } else {
            recommended.add(new Item(key, label, action));
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean hasAnyPresence(Business b) {
        return notBlank(b.getWebsiteUrl()) || notBlank(b.getWhatsappNumber()) || notBlank(b.getEmail())
                || notBlank(b.getFacebookUrl()) || notBlank(b.getInstagramUrl());
    }

    // ---- category module checklist -------------------------------------

    private interface DoneCheck {
        boolean test(ProfileCompletenessService s, UUID businessId);
    }

    private record CategoryItem(String key, String label, String action, DoneCheck check) {
        boolean done(ProfileCompletenessService s, UUID businessId) {
            return check.test(s, businessId);
        }
    }

    private static final DoneCheck HAS_OFFERINGS =
            (s, id) -> s.serviceRepository.existsByBusinessIdAndSection(id, ServiceSection.OFFERING);
    private static final DoneCheck HAS_TEAM = (s, id) -> s.teamRepository.existsByBusinessId(id);
    private static final DoneCheck HAS_MENU = (s, id) -> s.menuRepository.existsByBusinessId(id);
    private static final DoneCheck HAS_PRODUCTS = (s, id) -> s.productRepository.existsByBusinessId(id);

    private static List<CategoryItem> categoryItems(CategoryKind kind) {
        return switch (kind) {
            case RESTAURANT -> List.of(
                    new CategoryItem("menu", "Menu items", "Add a few menu items", HAS_MENU));
            case CLINIC -> List.of(
                    new CategoryItem("services", "Services", "List the services you offer", HAS_OFFERINGS),
                    new CategoryItem("team", "Doctors", "Add your doctors / specialists", HAS_TEAM));
            case SALON -> List.of(
                    new CategoryItem("services", "Services", "List the services you offer", HAS_OFFERINGS));
            case RETAIL -> List.of(
                    new CategoryItem("products", "Featured products", "Showcase a few products", HAS_PRODUCTS));
            case GYM -> List.of(
                    new CategoryItem("services", "Membership plans", "Add your membership plans", HAS_OFFERINGS),
                    new CategoryItem("team", "Trainers", "Add your trainers", HAS_TEAM));
            case GENERAL -> List.of(
                    new CategoryItem("services", "Services", "List the services you offer", HAS_OFFERINGS));
        };
    }
}
