package com.bdreview.platform.community.moderation;

import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.notification.NotificationChannel;
import com.bdreview.platform.notification.NotificationService;
import com.bdreview.platform.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Flips timed restrictions to EXPIRED once their end passes (every minute). Enforcement never
 * waits for this job — CommunityPolicyService checks {@code endsAt} directly — so it only keeps
 * the admin lists and the member's /account notice tidy, and tells the member they're back.
 */
@Component
public class CommunityRestrictionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CommunityRestrictionExpiryJob.class);

    private final CommunityRestrictionRepository restrictionRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    public CommunityRestrictionExpiryJob(CommunityRestrictionRepository restrictionRepository,
                                         AuditLogService auditLogService,
                                         NotificationService notificationService) {
        this.restrictionRepository = restrictionRepository;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void expireRestrictions() {
        List<CommunityRestriction> expired = restrictionRepository.findExpired(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        restrictionRepository.markExpired(expired.stream().map(CommunityRestriction::getId).toList());
        for (CommunityRestriction r : expired) {
            auditLogService.recordSystem("COMMUNITY_USER", r.getUserId(), "RESTRICTION_EXPIRED",
                    "Scheduled expiry", Map.of("restrictionId", r.getId(), "type", r.getType().name(), "status", "ACTIVE"),
                    Map.of("restrictionId", r.getId(), "type", r.getType().name(), "status", "EXPIRED"));
            if (r.getType() != CommunityRestriction.Type.WARN) {
                try {
                    notificationService.create(r.getUserId(), NotificationType.COMMUNITY_RESTRICTION,
                            "Your community restriction has ended",
                            "Your " + r.getType().name().toLowerCase(Locale.ROOT) + " has ended — you can post and comment again.",
                            "COMMUNITY_RESTRICTION", r.getId(), NotificationChannel.IN_APP);
                } catch (RuntimeException e) {
                    log.warn("Could not notify {} about an expired restriction", r.getUserId(), e);
                }
            }
        }
        log.info("Expired {} community restriction(s)", expired.size());
    }
}
