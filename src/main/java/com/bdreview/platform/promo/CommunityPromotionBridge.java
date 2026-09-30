package com.bdreview.platform.promo;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.catalog.MenuItem;
import com.bdreview.platform.catalog.MenuItemRepository;
import com.bdreview.platform.community.BusinessPostSupport;
import com.bdreview.platform.community.CommunityPost;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.promo.PromoEnums.BusinessPostStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Implements the community feed's promotion seam (V58) — see {@link BusinessPostSupport}. */
@Component
public class CommunityPromotionBridge implements BusinessPostSupport {

    private final BusinessPostRepository businessPostRepository;
    private final CommunityPostRepository communityPostRepository;
    private final OfferRepository offerRepository;
    private final MenuItemRepository menuItemRepository;
    private final PromoCreativeRepository creativeRepository;
    private final BusinessRepository businessRepository;
    private final SponsoredService sponsoredService;
    private final PromoAccess access;
    private final JdbcTemplate jdbc;

    public CommunityPromotionBridge(BusinessPostRepository businessPostRepository, CommunityPostRepository communityPostRepository,
                                    OfferRepository offerRepository, MenuItemRepository menuItemRepository,
                                    PromoCreativeRepository creativeRepository, BusinessRepository businessRepository,
                                    SponsoredService sponsoredService, PromoAccess access, JdbcTemplate jdbc) {
        this.businessPostRepository = businessPostRepository;
        this.communityPostRepository = communityPostRepository;
        this.offerRepository = offerRepository;
        this.menuItemRepository = menuItemRepository;
        this.creativeRepository = creativeRepository;
        this.businessRepository = businessRepository;
        this.sponsoredService = sponsoredService;
        this.access = access;
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, BusinessPostView> views(Collection<UUID> postIds, UUID viewerUserId) {
        List<BusinessPost> rows = businessPostRepository.findByPostIdIn(postIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CommunityPost> posts = communityPostRepository.findAllById(postIds).stream()
                .collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        Map<UUID, Offer> offers = byId(rows.stream().map(BusinessPost::getOfferId), offerRepository::findAllById, Offer::getId);
        Map<UUID, MenuItem> menuItems = byId(rows.stream().map(BusinessPost::getMenuItemId), menuItemRepository::findAllById, MenuItem::getId);
        Map<UUID, PromoCreative> creatives = byId(rows.stream().map(BusinessPost::getCreativeId), creativeRepository::findAllById, PromoCreative::getId);
        Map<UUID, Business> businesses = byId(rows.stream().map(BusinessPost::getBusinessId), businessRepository::findAllById, Business::getId);
        Set<UUID> interested = new HashSet<>();
        if (viewerUserId != null) {
            List<Object> args = new ArrayList<>();
            args.add(viewerUserId);
            rows.forEach(r -> args.add(r.getPostId()));
            String placeholders = String.join(",", Collections.nCopies(rows.size(), "?"));
            interested.addAll(jdbc.queryForList("SELECT post_id FROM business_post_interest WHERE user_id = ? AND post_id IN ("
                    + placeholders + ")", UUID.class, args.toArray()));
        }

        Map<UUID, BusinessPostView> out = new HashMap<>();
        for (BusinessPost bp : rows) {
            BusinessPostStatus status = BusinessPostService.effectiveStatus(bp, posts.get(bp.getPostId()));
            Offer offer = offers.get(bp.getOfferId());
            MenuItem item = menuItems.get(bp.getMenuItemId());
            boolean offerEnded = bp.getOfferId() != null && (offer == null || !offer.isCurrentlyActive());
            boolean expired = status == BusinessPostStatus.EXPIRED || (status == BusinessPostStatus.PUBLISHED && offerEnded);
            if (expired) {
                status = BusinessPostStatus.EXPIRED;
            }
            Business business = businesses.get(bp.getBusinessId());
            boolean isOwner = viewerUserId != null && business != null && viewerUserId.equals(business.getOwnerUserId());
            PromoCreative creative = creatives.get(bp.getCreativeId());
            out.put(bp.getPostId(), new BusinessPostView(
                    bp.getType().name(),
                    status.name(),
                    expired,
                    cta(bp),
                    offer == null ? null : new BusinessPostView.OfferRef(offer.getId(), offer.getTitle(),
                            offer.getOfferType() == null ? null : offer.getOfferType().name(), offer.getDiscountValue(),
                            offer.getOriginalPrice(), offer.getOfferPrice(), offer.getValidUntil(), offer.isCurrentlyActive()),
                    item == null ? null : new BusinessPostView.MenuItemRef(item.getId(), item.getName(), item.getPrice(),
                            item.getPriceText(), item.getPhotoUrl(), item.isAvailable(), item.isOrderingEnabled()),
                    bp.getEventStart(), bp.getEventEnd(),
                    creative == null ? null : creative.getId(),
                    creative == null ? null : creative.getSquareUrl(),
                    creative == null ? null : creative.getStoryUrl(),
                    creative == null ? null : creative.getOgUrl(),
                    bp.getInterestedCount(),
                    interested.contains(bp.getPostId()),
                    // The rejection reason is only for the owner's eyes.
                    isOwner ? bp.getRejectionReason() : null,
                    isOwner && status == BusinessPostStatus.PUBLISHED && access.settings().isBoostsEnabled()
                            && (bp.getOfferId() == null || BoostService.offerHasRunway(offer, java.time.Instant.now()))));
        }
        return out;
    }

    static String cta(BusinessPost bp) {
        return switch (bp.getType()) {
            case OFFER -> "GET_OFFER";
            case MENU_ITEM -> "ORDER";
            case EVENT -> "INTERESTED";
            default -> "VIEW_BUSINESS";
        };
    }

    @Override
    public List<SponsoredSlot> sponsoredForFeed(FeedViewer viewer, int count, Collection<UUID> excludePostIds) {
        return sponsoredService.forFeed(
                        new SponsoredService.Viewer(viewer.areaId(), viewer.lat(), viewer.lng(), viewer.sessionId()),
                        count, excludePostIds).stream()
                .map(s -> new SponsoredSlot(s.post().getPostId(), new SponsoredInfo(s.boost().getId(), s.why())))
                .toList();
    }

    @Override
    public int sponsoredFeedRatio() {
        var p = access.settings();
        return p.isBoostsEnabled() ? p.getSponsoredFeedRatio() : 0;
    }

    private static <T> Map<UUID, T> byId(java.util.stream.Stream<UUID> ids, Function<List<UUID>, List<T>> load, Function<T, UUID> key) {
        List<UUID> distinct = ids.filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return new HashMap<>();
        }
        return load.apply(distinct).stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a, HashMap::new));
    }
}
