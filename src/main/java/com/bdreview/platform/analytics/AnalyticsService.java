package com.bdreview.platform.analytics;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 first-party analytics: a fire-and-forget insert on the write side,
 * and a single GROUP BY COUNT on the read side. No queues, no row scans in Java.
 */
@Service
public class AnalyticsService {

    /** Within this window a repeat PROFILE_VIEW from the same session is dropped. */
    private static final Duration VIEW_DEDUPE_WINDOW = Duration.ofMinutes(30);

    private final BusinessEventRepository eventRepository;
    private final BusinessRepository businessRepository;

    public AnalyticsService(BusinessEventRepository eventRepository, BusinessRepository businessRepository) {
        this.eventRepository = eventRepository;
        this.businessRepository = businessRepository;
    }

    // ---- write ------------------------------------------------------------

    /** Records one event. Silently no-ops for an unknown/deleted business or a deduped PROFILE_VIEW. */
    @Transactional
    public void record(UUID businessId, BusinessEventType type, String rawSessionId) {
        boolean liveBusiness = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .isPresent();
        if (!liveBusiness) {
            return; // don't create events for listings that don't exist / are archived
        }
        String sessionId = normalizeSession(rawSessionId);

        if (type == BusinessEventType.PROFILE_VIEW && sessionId != null) {
            Instant since = Instant.now().minus(VIEW_DEDUPE_WINDOW);
            if (eventRepository.existsByBusinessIdAndSessionIdAndEventTypeAndCreatedAtAfter(
                    businessId, sessionId, BusinessEventType.PROFILE_VIEW, since)) {
                return;
            }
        }

        eventRepository.save(BusinessEvent.builder()
                .businessId(businessId)
                .eventType(type)
                .sessionId(sessionId)
                .build());
    }

    // ---- read (owner / admin only) --------------------------------------

    @Transactional(readOnly = true)
    public AnalyticsResponse forOwner(UUID requesterUserId, boolean requesterIsAdmin, UUID businessId, String range) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!requesterIsAdmin && !business.getOwnerUserId().equals(requesterUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }

        String normalized = normalizeRange(range);
        Instant from = fromFor(normalized);
        List<Object[]> rows = (from == null)
                ? eventRepository.aggregateAllTime(businessId)
                : eventRepository.aggregateSince(businessId, from);

        Map<BusinessEventType, Long> counts = new EnumMap<>(BusinessEventType.class);
        for (Object[] row : rows) {
            counts.put(BusinessEventType.valueOf((String) row[0]), ((Number) row[1]).longValue());
        }

        return new AnalyticsResponse(
                normalized, from,
                counts.getOrDefault(BusinessEventType.PROFILE_VIEW, 0L),
                counts.getOrDefault(BusinessEventType.PHONE_CLICK, 0L),
                counts.getOrDefault(BusinessEventType.WHATSAPP_CLICK, 0L),
                counts.getOrDefault(BusinessEventType.DIRECTIONS_CLICK, 0L),
                counts.getOrDefault(BusinessEventType.WEBSITE_CLICK, 0L));
    }

    private static String normalizeRange(String range) {
        if (range == null) {
            return "30d";
        }
        return switch (range.toLowerCase()) {
            case "7d", "30d", "all" -> range.toLowerCase();
            default -> throw new BadRequestException("range must be one of: 7d, 30d, all");
        };
    }

    private static Instant fromFor(String range) {
        return switch (range) {
            case "7d" -> Instant.now().minus(Duration.ofDays(7));
            case "30d" -> Instant.now().minus(Duration.ofDays(30));
            default -> null; // "all"
        };
    }

    private static String normalizeSession(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > 64 ? t.substring(0, 64) : t;
    }
}
