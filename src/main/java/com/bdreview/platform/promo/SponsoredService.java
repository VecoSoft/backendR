package com.bdreview.platform.promo;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.business.BusinessResponse;
import com.bdreview.platform.business.BusinessService;
import com.bdreview.platform.community.CommunityPost;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.promo.PromoEnums.BusinessPostStatus;
import com.bdreview.platform.search.SearchReferenceData;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chooses which boosts may be shown where (V58). A boost is only ever served when ALL of these hold:
 * the Boost feature is on; it is ACTIVE and inside its window; its post is PUBLISHED (not expired,
 * rejected or removed); a linked offer is still live; the business isn't promotion-suspended;
 * it's under its pacing line; the viewer matches its targeting (selected area, consented
 * location — or, when the viewer shared nothing, city level); and the viewer's session hasn't
 * seen it {@code frequencyCapPerDay} times today.
 *
 * <p>This class only ever PICKS paid placements. It never reads or writes review, rating,
 * verification or organic-ranking data, and callers insert its results beside organic results —
 * never into them.
 */
@Service
public class SponsoredService {

    /** A viewer "in" an area-targeted boost's area when within this distance of the area's centre. */
    static final double AREA_MATCH_KM = 3.0;
    /** Pacing tolerance above the even-delivery line, plus a small head start so a new boost isn't starved. */
    static final double PACING_SLACK = 1.15;
    static final int PACING_HEAD_START = 25;
    public static final int FEATURED_MAX = 6;

    public record Viewer(UUID areaId, Double lat, Double lng, String sessionId) {
        boolean sharedNothing() {
            return areaId == null && (lat == null || lng == null);
        }
    }

    public record Served(Boost boost, BusinessPost post, Business business, String why) {
    }

    public record SponsoredBusiness(BusinessResponse business, UUID boostId, UUID postId, String why) {
    }

    private final BoostRepository boostRepository;
    private final BusinessPostRepository businessPostRepository;
    private final CommunityPostRepository communityPostRepository;
    private final BusinessRepository businessRepository;
    private final BusinessService businessService;
    private final OfferRepository offerRepository;
    private final PromoAccess access;
    private final PromoCounters counters;
    private final SearchReferenceData referenceData;

    public SponsoredService(BoostRepository boostRepository, BusinessPostRepository businessPostRepository,
                            CommunityPostRepository communityPostRepository, BusinessRepository businessRepository,
                            BusinessService businessService, OfferRepository offerRepository, PromoAccess access,
                            PromoCounters counters, SearchReferenceData referenceData) {
        this.boostRepository = boostRepository;
        this.businessPostRepository = businessPostRepository;
        this.communityPostRepository = communityPostRepository;
        this.businessRepository = businessRepository;
        this.businessService = businessService;
        this.offerRepository = offerRepository;
        this.access = access;
        this.counters = counters;
        this.referenceData = referenceData;
    }

    // -----------------------------------------------------------------
    // Placements
    // -----------------------------------------------------------------

    /** Community feed: up to {@code count} boosts, one per business, never any of {@code excludePostIds}. */
    public List<Served> forFeed(Viewer viewer, int count, Collection<UUID> excludePostIds) {
        if (count <= 0 || !access.settings().isBoostsEnabled()) {
            return List.of();
        }
        return pick(viewer, count, s -> !excludePostIds.contains(s.post().getPostId()));
    }

    /** Home "Featured nearby" carousel (max 6). Empty when the setting is off or nothing is eligible. */
    public List<SponsoredBusiness> featuredNearby(Viewer viewer) {
        CommunitySettings.Promotions p = access.settings();
        if (!p.isBoostsEnabled() || !p.isFeaturedNearbyEnabled()) {
            return List.of();
        }
        return toBusinesses(pick(viewer, FEATURED_MAX, s -> true));
    }

    /**
     * Search: at most ONE sponsored result, only when the boosted business matches the query's
     * category kind(s) or its area. Returned separately from the organic results, which the
     * caller must leave exactly as they were.
     */
    public Optional<SponsoredBusiness> forSearch(Viewer viewer, Set<String> categoryKinds, UUID queryAreaId) {
        CommunitySettings.Promotions p = access.settings();
        if (!p.isBoostsEnabled() || !p.isSponsoredInSearch() || (categoryKinds.isEmpty() && queryAreaId == null)) {
            return Optional.empty();
        }
        List<Served> served = pick(viewer, 1, s -> {
            boolean kindMatch = !categoryKinds.isEmpty()
                    && categoryKinds.contains(s.business().getCategory().getKind().name());
            boolean areaMatch = queryAreaId != null && (queryAreaId.equals(s.business().getArea().getId())
                    || s.boost().getTargetAreaIds().contains(queryAreaId));
            return categoryKinds.isEmpty() ? areaMatch : kindMatch && (queryAreaId == null || areaMatch);
        });
        return toBusinesses(served).stream().findFirst();
    }

    // -----------------------------------------------------------------
    // Eligibility
    // -----------------------------------------------------------------

    /** Every boost that could be served right now, ignoring viewer targeting and caps — used by admin views/tests too. */
    public List<Served> eligibleIgnoringViewer() {
        return candidates().stream().map(c -> new Served(c.boost, c.post, c.business, null)).toList();
    }

    private record Candidate(Boost boost, BusinessPost post, Business business) {
    }

    private List<Candidate> candidates() {
        Instant now = Instant.now();
        List<Boost> live = boostRepository.findLive(now);
        if (live.isEmpty()) {
            return List.of();
        }
        Map<UUID, BusinessPost> posts = businessPostRepository.findByPostIdIn(live.stream().map(Boost::getPostId).toList())
                .stream().collect(Collectors.toMap(BusinessPost::getPostId, Function.identity()));
        Map<UUID, CommunityPost> communityPosts = communityPostRepository.findAllById(posts.keySet()).stream()
                .collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        Map<UUID, Business> businesses = businessRepository.findAllByIdInWithPlace(
                        live.stream().map(Boost::getBusinessId).distinct().toList())
                .stream().collect(Collectors.toMap(Business::getId, Function.identity()));
        Set<UUID> offerIds = posts.values().stream().map(BusinessPost::getOfferId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Offer> offers = offerIds.isEmpty() ? Map.of() : offerRepository.findAllById(offerIds).stream()
                .collect(Collectors.toMap(Offer::getId, Function.identity()));

        List<Candidate> out = new ArrayList<>();
        for (Boost b : live) {
            BusinessPost bp = posts.get(b.getPostId());
            Business business = businesses.get(b.getBusinessId());
            if (bp == null || business == null || business.getDeletedAt() != null) {
                continue;
            }
            if (BusinessPostService.effectiveStatus(bp, communityPosts.get(bp.getPostId())) != BusinessPostStatus.PUBLISHED) {
                continue;
            }
            if (bp.getOfferId() != null) {
                Offer offer = offers.get(bp.getOfferId());
                if (offer == null || !offer.isCurrentlyActive()) {
                    continue;
                }
            }
            if (access.isRestricted(business.getId()) || !underPacingLine(b, now)) {
                continue;
            }
            out.add(new Candidate(b, bp, business));
        }
        return out;
    }

    /** Even delivery: impressions served may not run ahead of (elapsed share × estimate) by more than the slack. */
    static boolean underPacingLine(Boost b, Instant now) {
        double total = Math.max(1, b.getEndAt().toEpochMilli() - b.getStartAt().toEpochMilli());
        double elapsed = Math.min(1.0, Math.max(0, now.toEpochMilli() - b.getStartAt().toEpochMilli()) / total);
        return b.getImpressionsServed() < b.getEstImpressions() * elapsed * PACING_SLACK + PACING_HEAD_START;
    }

    /** How far behind its even-delivery line a boost is — the most behind is served first. */
    static double deficit(Boost b, Instant now) {
        double total = Math.max(1, b.getEndAt().toEpochMilli() - b.getStartAt().toEpochMilli());
        double elapsed = Math.min(1.0, Math.max(0, now.toEpochMilli() - b.getStartAt().toEpochMilli()) / total);
        return (b.getEstImpressions() * elapsed - b.getImpressionsServed()) / Math.max(1, b.getEstImpressions());
    }

    private List<Served> pick(Viewer viewer, int count, java.util.function.Predicate<Served> extra) {
        Instant now = Instant.now();
        int cap = access.settings().getFrequencyCapPerDay();
        String session = PromoCounters.sessionHash(viewer.sessionId());
        List<Served> matching = new ArrayList<>();
        for (Candidate c : candidates()) {
            String why = targetingReason(c, viewer);
            if (why == null) {
                continue;
            }
            Served s = new Served(c.boost, c.post, c.business, why);
            if (!extra.test(s)) {
                continue;
            }
            if (session != null && counters.servedToday(session, c.boost.getId()) >= cap) {
                continue;
            }
            matching.add(s);
        }
        matching.sort(Comparator.comparingDouble((Served s) -> deficit(s.boost(), now)).reversed());
        List<Served> chosen = new ArrayList<>();
        Set<UUID> businesses = new HashSet<>();
        for (Served s : matching) {
            if (chosen.size() >= count) {
                break;
            }
            if (businesses.add(s.business().getId())) {
                chosen.add(s);
                if (session != null) {
                    counters.recordServed(session, s.boost().getId());
                }
            }
        }
        return chosen;
    }

    /**
     * Null when the viewer is outside the boost's targeting; otherwise the plain "Why am I seeing
     * this?" answer. Targeting is by area or distance only — never by anything the viewer did.
     */
    String targetingReason(Candidate c, Viewer viewer) {
        Boost b = c.boost;
        Map<UUID, String> areaNames = areaNames();
        if (viewer.sharedNothing()) {
            String city = c.business.getCity() == null ? "your city" : c.business.getCity().getName();
            return "This business paid to promote this post to people in " + city + ".";
        }
        if (viewer.areaId() != null && b.getTargetAreaIds().contains(viewer.areaId())) {
            return "This business paid to show this to people in " + areaNames.getOrDefault(viewer.areaId(), "your area") + ".";
        }
        if (viewer.lat() != null && viewer.lng() != null) {
            if (b.isRadiusTargeted()
                    && km(viewer.lat(), viewer.lng(), b.getCenterLat(), b.getCenterLng()) <= b.getRadiusKm()) {
                return "This business paid to show this to people within " + b.getRadiusKm() + " km of it.";
            }
            for (UUID areaId : b.getTargetAreaIds()) {
                double[] centre = areaCentres().get(areaId);
                if (centre != null && km(viewer.lat(), viewer.lng(), centre[0], centre[1]) <= AREA_MATCH_KM) {
                    return "This business paid to show this to people in " + areaNames.getOrDefault(areaId, "this area") + ".";
                }
            }
        }
        // Viewer chose an area but not a location: a radius boost matches when that area's centre is inside it.
        if (viewer.areaId() != null && (viewer.lat() == null || viewer.lng() == null) && b.isRadiusTargeted()) {
            double[] centre = areaCentres().get(viewer.areaId());
            if (centre != null && km(centre[0], centre[1], b.getCenterLat(), b.getCenterLng()) <= b.getRadiusKm()) {
                return "This business paid to show this to people within " + b.getRadiusKm() + " km of it.";
            }
        }
        return null;
    }

    private Map<UUID, String> areaNames() {
        Map<UUID, String> out = new HashMap<>();
        referenceData.get().areasByLowerName().values().forEach(a -> out.put(a.id(), a.name()));
        return out;
    }

    private Map<UUID, double[]> areaCentres() {
        Map<UUID, double[]> out = new HashMap<>();
        referenceData.get().areasByLowerName().values().forEach(a -> {
            if (a.centroidLat() != null && a.centroidLng() != null) {
                out.put(a.id(), new double[]{a.centroidLat(), a.centroidLng()});
            }
        });
        return out;
    }

    static double km(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private List<SponsoredBusiness> toBusinesses(List<Served> served) {
        if (served.isEmpty()) {
            return List.of();
        }
        Map<UUID, BusinessResponse> cards = businessService.listingResponsesByIds(
                        served.stream().map(s -> s.business().getId()).toList())
                .stream().collect(Collectors.toMap(BusinessResponse::id, Function.identity(), (a, b) -> a));
        List<SponsoredBusiness> out = new ArrayList<>();
        for (Served s : served) {
            BusinessResponse card = cards.get(s.business().getId());
            if (card != null) {
                out.add(new SponsoredBusiness(card, s.boost().getId(), s.post().getPostId(), s.why()));
            }
        }
        return out;
    }
}
