package com.bdreview.platform.promo;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.promo.PromoEnums.PromoEventType;
import com.bdreview.platform.promo.PromoEnums.PromoSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Promotion analytics ingestion (V58). Two entry points:
 * <ul>
 *   <li>{@link #ingestBeacon} — the browser's batched beacon (impressions, clicks, shares and the
 *       profile-visit / call / directions / message actions that followed a promo). Deduped per
 *       session + event + post for 30 minutes; impressions need a session id.</li>
 *   <li>{@link #recordConversion} — server-side only (offer claim, order, booking) when the
 *       request carried a promo attribution; never accepted from the beacon.</li>
 * </ul>
 * Privacy: no user id and no IP is ever written — only a daily-rotating session hash, and for
 * conversions the id of the order/claim/booking itself (so attribution joins by that id).
 */
@Service
public class PromoEventService {

    private static final Logger log = LoggerFactory.getLogger(PromoEventService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    static final int MAX_BATCH = 50;
    static final EnumSet<PromoEventType> CLIENT_EVENTS = EnumSet.of(PromoEventType.IMPRESSION, PromoEventType.CLICK,
            PromoEventType.SHARE, PromoEventType.PROFILE_VISIT, PromoEventType.CALL, PromoEventType.DIRECTIONS,
            PromoEventType.MESSAGE);
    static final EnumSet<PromoEventType> SERVER_EVENTS = EnumSet.of(PromoEventType.ORDER, PromoEventType.BOOKING,
            PromoEventType.OFFER_CLAIM);
    private static final Pattern REF = Pattern.compile("^(share|wa|fb|feed|home|search|qr|promo_[0-9a-fA-F-]{36})$");
    private static final UUID NO_BOOST = new UUID(0L, 0L);

    public record BeaconEvent(UUID postId, UUID boostId, String event, String source, String ref) {
    }

    public record Beacon(List<BeaconEvent> events) {
    }

    /** Promo attribution carried on a claim/order/booking request (from the share link or a promo CTA). */
    public record Attribution(UUID postId, UUID boostId, String ref) {
        public boolean present() {
            return postId != null;
        }
    }

    private final JdbcTemplate jdbc;
    private final BoostRepository boostRepository;
    private final PromoCounters counters;

    public PromoEventService(JdbcTemplate jdbc, BoostRepository boostRepository, PromoCounters counters) {
        this.jdbc = jdbc;
        this.boostRepository = boostRepository;
        this.counters = counters;
    }

    /** Returns how many events were recorded (after validation and dedupe). */
    @Transactional
    public int ingestBeacon(Beacon beacon, String sessionId) {
        if (beacon == null || beacon.events() == null) {
            return 0;
        }
        if (beacon.events().size() > MAX_BATCH) {
            throw new BadRequestException("Too many events in one batch (max " + MAX_BATCH + ").");
        }
        String session = PromoCounters.sessionHash(sessionId);
        int recorded = 0;
        for (BeaconEvent e : beacon.events()) {
            PromoEventType type = parse(e.event());
            if (type == null || !CLIENT_EVENTS.contains(type) || e.postId() == null) {
                continue;
            }
            if (session == null && type == PromoEventType.IMPRESSION) {
                continue; // an impression we can't dedupe isn't worth counting
            }
            UUID businessId = businessOf(e.postId());
            if (businessId == null) {
                continue;
            }
            UUID boostId = validBoost(e.boostId(), e.postId());
            String dedupeTarget = e.postId() + ":" + (boostId == null ? "" : boostId);
            if (session != null && !counters.firstWithin30Minutes(session, type.name(), dedupeTarget)) {
                continue;
            }
            insert(e.postId(), boostId, businessId, type, source(e.source(), e.ref()), cleanRef(e.ref()), session, null);
            if (boostId != null && type == PromoEventType.IMPRESSION) {
                boostRepository.addImpressions(boostId, 1);
            }
            recorded++;
        }
        return recorded;
    }

    /**
     * Server-side conversion (claim / order / booking) — never throws; attribution is best-effort.
     * Only counted when the promoted post belongs to the business the order/claim went to, so a
     * made-up attribution can't inflate someone else's numbers.
     */
    public void recordConversion(Attribution a, PromoEventType type, UUID targetId, UUID conversionBusinessId) {
        if (a == null || !a.present() || !SERVER_EVENTS.contains(type)) {
            return;
        }
        try {
            UUID businessId = businessOf(a.postId());
            if (businessId == null || !businessId.equals(conversionBusinessId)) {
                return;
            }
            insert(a.postId(), validBoost(a.boostId(), a.postId()), businessId, type, source(null, a.ref()), cleanRef(a.ref()),
                    null, targetId);
        } catch (Exception ex) {
            log.warn("Could not record promo conversion {} for post {}: {}", type, a.postId(), ex.getMessage());
        }
    }

    /** Offer claims: the conversion's business is the offer's business. */
    public void recordOfferClaim(Attribution a, UUID claimId, UUID offerId) {
        if (a == null || !a.present()) {
            return;
        }
        List<UUID> ids = jdbc.queryForList("SELECT business_id FROM offer WHERE id = ?", UUID.class, offerId);
        if (!ids.isEmpty()) {
            recordConversion(a, PromoEventType.OFFER_CLAIM, claimId, ids.get(0));
        }
    }

    private void insert(UUID postId, UUID boostId, UUID businessId, PromoEventType type, PromoSource source, String ref,
                        String session, UUID targetId) {
        jdbc.update("""
                INSERT INTO promo_event (post_id, boost_id, business_id, event, session_hash, source, ref, target_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, postId, boostId, businessId, type.name(), session, source.name(), ref, targetId);
        jdbc.update("""
                INSERT INTO promo_stats_daily (day, post_id, boost_id, business_id, event, count) VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT (day, post_id, boost_id, event) DO UPDATE SET count = promo_stats_daily.count + 1
                """, LocalDate.now(ZONE), postId, boostId == null ? NO_BOOST : boostId, businessId, type.name());
    }

    private UUID businessOf(UUID postId) {
        List<UUID> ids = jdbc.queryForList("SELECT business_id FROM business_post WHERE post_id = ?", UUID.class, postId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private UUID validBoost(UUID boostId, UUID postId) {
        if (boostId == null) {
            return null;
        }
        return boostRepository.findById(boostId).filter(b -> b.getPostId().equals(postId)).map(Boost::getId).orElse(null);
    }

    private static PromoEventType parse(String s) {
        try {
            return s == null ? null : PromoEventType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String cleanRef(String ref) {
        return ref != null && REF.matcher(ref.trim()).matches() ? ref.trim().toLowerCase(Locale.ROOT) : null;
    }

    /** Source from an explicit value, else inferred from the link ref. */
    static PromoSource source(String source, String ref) {
        if (source != null) {
            try {
                return PromoSource.valueOf(source.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the ref
            }
        }
        String r = cleanRef(ref);
        if (r == null) {
            return PromoSource.FEED;
        }
        return switch (r) {
            case "share", "wa", "fb" -> PromoSource.SHARE_LINK;
            case "home" -> PromoSource.HOME;
            case "search" -> PromoSource.SEARCH;
            case "feed" -> PromoSource.FEED;
            default -> PromoSource.EXTERNAL; // promo_<creativeId> (QR / downloaded creative), qr
        };
    }
}
