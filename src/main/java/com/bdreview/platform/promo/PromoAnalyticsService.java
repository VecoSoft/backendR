package com.bdreview.platform.promo;

import com.bdreview.platform.community.CommunityPost;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.promo.PromoEnums.PromoEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owner "Promotions" dashboard numbers (V58), read only from the promo_stats_daily rollup — never
 * a scan of raw events. Conversions (orders / bookings / offer claims) only count when the order
 * or claim itself carried the promo attribution.
 */
@Service
public class PromoAnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    static final List<PromoEventType> CONVERSIONS = List.of(PromoEventType.ORDER, PromoEventType.BOOKING, PromoEventType.OFFER_CLAIM);

    public record DayPoint(LocalDate day, int impressions, int clicks, int conversions) {
    }

    public record BoostStat(UUID boostId, String status, String packageName, BigDecimal priceBdt, BigDecimal paidAmount,
                            int estImpressions, int impressionsServed, Instant startAt, Instant endAt,
                            /** paid ÷ (clicks + conversions) from this boost; null when nothing to divide by. */
                            BigDecimal costPerAction, int actions) {
    }

    public record Item(UUID postId, String title, String type, String status, Instant createdAt,
                       Map<String, Integer> totals, List<DayPoint> daily, List<BoostStat> boosts) {
    }

    /** "Your Zinger offer: 3,240 views → 41 clicks → 12 orders". Null when nothing has any views yet. */
    public record Headline(UUID postId, String title, int views, int clicks, int conversions, String conversionEvent) {
    }

    public record Analytics(int days, Headline headline, Map<String, Integer> totals, List<DayPoint> daily, List<Item> items,
                            int postsRemainingThisWeek) {
    }

    private final JdbcTemplate jdbc;
    private final PromoAccess access;
    private final BusinessPostRepository businessPostRepository;
    private final CommunityPostRepository communityPostRepository;
    private final BoostRepository boostRepository;
    private final BusinessPostService businessPostService;

    public PromoAnalyticsService(JdbcTemplate jdbc, PromoAccess access, BusinessPostRepository businessPostRepository,
                                 CommunityPostRepository communityPostRepository, BoostRepository boostRepository,
                                 BusinessPostService businessPostService) {
        this.jdbc = jdbc;
        this.access = access;
        this.businessPostRepository = businessPostRepository;
        this.communityPostRepository = communityPostRepository;
        this.boostRepository = boostRepository;
        this.businessPostService = businessPostService;
    }

    private record Row(LocalDate day, UUID postId, UUID boostId, String event, int count) {
    }

    @Transactional(readOnly = true)
    public Analytics forBusiness(UUID userId, UUID businessId, int days) {
        access.requireOwned(userId, businessId);
        int window = Math.min(Math.max(days, 7), 90);
        LocalDate since = LocalDate.now(ZONE).minusDays(window - 1L);
        List<Row> rows = jdbc.query("""
                SELECT day, post_id, boost_id, event, count FROM promo_stats_daily
                WHERE business_id = ? AND day >= ?
                """, (rs, n) -> new Row(rs.getObject(1, LocalDate.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(5)), businessId, since);
        // All-time totals per boost (a boost may have started before the window).
        Map<UUID, Map<String, Integer>> boostTotals = new HashMap<>();
        jdbc.query("""
                SELECT boost_id, event, sum(count) FROM promo_stats_daily
                WHERE business_id = ? AND boost_id <> '00000000-0000-0000-0000-000000000000'
                GROUP BY boost_id, event
                """, rs -> {
            boostTotals.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>()).put(rs.getString(2), rs.getInt(3));
        }, businessId);

        List<BusinessPost> posts = businessPostRepository.findByBusinessIdOrderByCreatedAtDesc(businessId);
        Map<UUID, CommunityPost> communityPosts = communityPostRepository.findAllById(
                posts.stream().map(BusinessPost::getPostId).toList()).stream()
                .collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        Map<UUID, List<Boost>> boostsByPost = boostRepository.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .collect(Collectors.groupingBy(Boost::getPostId));

        List<Item> items = new ArrayList<>();
        for (BusinessPost bp : posts) {
            CommunityPost cp = communityPosts.get(bp.getPostId());
            if (cp != null && cp.getDeletedAt() != null) {
                continue;
            }
            List<Row> mine = rows.stream().filter(r -> r.postId().equals(bp.getPostId())).toList();
            List<BoostStat> boosts = boostsByPost.getOrDefault(bp.getPostId(), List.of()).stream()
                    .map(b -> boostStat(b, boostTotals.getOrDefault(b.getId(), Map.of()))).toList();
            items.add(new Item(bp.getPostId(), titleOf(cp), bp.getType().name(),
                    BusinessPostService.effectiveStatus(bp, cp).name(), bp.getCreatedAt(), totals(mine), daily(mine, since, window),
                    boosts));
        }
        Headline headline = items.stream()
                .filter(i -> i.totals().getOrDefault("IMPRESSION", 0) > 0)
                .max(Comparator.comparingInt(i -> i.totals().getOrDefault("IMPRESSION", 0)))
                .map(this::headline)
                .orElse(null);
        return new Analytics(window, headline, totals(rows), daily(rows, since, window), items,
                businessPostService.remainingThisWeek(businessId));
    }

    private Headline headline(Item i) {
        String best = "ORDER";
        int bestCount = -1;
        for (PromoEventType t : CONVERSIONS) {
            int c = i.totals().getOrDefault(t.name(), 0);
            if (c > bestCount) {
                best = t.name();
                bestCount = c;
            }
        }
        if ("OFFER".equals(i.type()) && i.totals().getOrDefault("OFFER_CLAIM", 0) >= bestCount) {
            best = "OFFER_CLAIM";
            bestCount = i.totals().getOrDefault("OFFER_CLAIM", 0);
        }
        return new Headline(i.postId(), i.title(), i.totals().getOrDefault("IMPRESSION", 0), i.totals().getOrDefault("CLICK", 0),
                Math.max(0, bestCount), best);
    }

    private static BoostStat boostStat(Boost b, Map<String, Integer> totals) {
        int actions = totals.getOrDefault("CLICK", 0) + CONVERSIONS.stream().mapToInt(t -> totals.getOrDefault(t.name(), 0)).sum();
        BigDecimal spent = b.getPaidAmount();
        BigDecimal cpa = spent == null || actions == 0 ? null : spent.divide(BigDecimal.valueOf(actions), 2, RoundingMode.HALF_UP);
        return new BoostStat(b.getId(), b.getStatus().name(), b.getPackageName(), b.getPriceBdt(), b.getPaidAmount(),
                b.getEstImpressions(), b.getImpressionsServed(), b.getStartAt(), b.getEndAt(), cpa, actions);
    }

    private static Map<String, Integer> totals(List<Row> rows) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (PromoEventType t : PromoEventType.values()) {
            out.put(t.name(), 0);
        }
        rows.forEach(r -> out.merge(r.event(), r.count(), Integer::sum));
        return out;
    }

    private static List<DayPoint> daily(List<Row> rows, LocalDate since, int window) {
        Map<LocalDate, int[]> byDay = new TreeMap<>();
        for (int d = 0; d < window; d++) {
            byDay.put(since.plusDays(d), new int[3]);
        }
        for (Row r : rows) {
            int[] acc = byDay.get(r.day());
            if (acc == null) {
                continue;
            }
            if ("IMPRESSION".equals(r.event())) {
                acc[0] += r.count();
            } else if ("CLICK".equals(r.event())) {
                acc[1] += r.count();
            } else if (CONVERSIONS.stream().anyMatch(t -> t.name().equals(r.event()))) {
                acc[2] += r.count();
            }
        }
        return byDay.entrySet().stream().map(e -> new DayPoint(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList();
    }

    private static String titleOf(CommunityPost cp) {
        if (cp == null) {
            return "Business post";
        }
        String t = cp.getTitle() != null && !cp.getTitle().isBlank() ? cp.getTitle() : cp.getBody();
        if (t == null || t.isBlank()) {
            return "Business post";
        }
        t = t.strip().replaceAll("\\s+", " ");
        return t.length() > 60 ? t.substring(0, 57).strip() + "…" : t;
    }
}
