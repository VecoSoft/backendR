package com.bdreview.platform.promo;

import com.bdreview.platform.community.CommunityContentStatus;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.promo.PromoEnums.BusinessPostStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Keeps promotion state honest (V58), every 2 minutes:
 * <ol>
 *   <li>business posts whose linked offer ended/was cancelled, whose event is over, or whose own
 *       lifetime passed → EXPIRED ("Expired" label, CTA disabled, out of every sponsored slot);</li>
 *   <li>posts approved/removed through the generic community moderation queue → mirrored onto
 *       the business_post row;</li>
 *   <li>boosts past their end date → ENDED.</li>
 * </ol>
 * Serving never depends on this job having run: every read also re-checks offer validity and
 * windows (see BusinessPostService#effectiveStatus, SponsoredService).
 */
@Component
public class PromoExpiryJob {
    /** V67 System → Health: every run is recorded (duration, result); setter-injected so unit tests may skip it. */
    private com.bdreview.platform.health.JobRunRecorder jobRuns;

    @org.springframework.beans.factory.annotation.Autowired
    void setJobRuns(com.bdreview.platform.health.JobRunRecorder jobRuns) {
        this.jobRuns = jobRuns;
    }

    private void tracked(String job, Runnable body) {
        if (jobRuns == null) {
            body.run();
        } else {
            jobRuns.track(job, body);
        }
    }


    private static final Logger log = LoggerFactory.getLogger(PromoExpiryJob.class);

    private final BusinessPostRepository businessPostRepository;
    private final CommunityPostRepository communityPostRepository;
    private final BusinessPostService businessPostService;
    private final BoostService boostService;

    public PromoExpiryJob(BusinessPostRepository businessPostRepository, CommunityPostRepository communityPostRepository,
                          BusinessPostService businessPostService, BoostService boostService) {
        this.businessPostRepository = businessPostRepository;
        this.communityPostRepository = communityPostRepository;
        this.businessPostService = businessPostService;
        this.boostService = boostService;
    }

    @Scheduled(fixedRate = 2, timeUnit = TimeUnit.MINUTES, initialDelay = 1)
    public void run() {
        tracked("promo-expiry", () -> {
            try {
                int expired = 0;
                for (UUID postId : businessPostRepository.findExpiredCandidates(Instant.now())) {
                    if (businessPostService.expire(postId, "Offer, event or promotion window ended")) {
                        expired++;
                    }
                }
                int reconciled = reconcile();
                int ended = boostService.endFinished();
                if (expired + reconciled + ended > 0) {
                    log.info("Promotion upkeep: {} post(s) expired, {} reconciled, {} boost(s) ended", expired, reconciled, ended);
                }
            } catch (Exception e) {
                log.warn("Promotion upkeep failed: {}", e.getMessage(), e);
            }
        });
    }

    /** Mirrors community-queue approvals/removals onto the promotion sidecar. */
    @Transactional
    int reconcile() {
        int n = 0;
        for (BusinessPost bp : businessPostRepository.findByStatusOrderByCreatedAtAsc(BusinessPostStatus.PENDING_REVIEW)) {
            var post = communityPostRepository.findById(bp.getPostId()).orElse(null);
            BusinessPostStatus effective = BusinessPostService.effectiveStatus(bp, post);
            if (effective != bp.getStatus()) {
                bp.setStatus(effective);
                if (effective == BusinessPostStatus.PUBLISHED && bp.getPublishedAt() == null) {
                    bp.setPublishedAt(Instant.now());
                }
                businessPostRepository.save(bp);
                n++;
            }
        }
        for (BusinessPost bp : businessPostRepository.findByStatusOrderByCreatedAtAsc(BusinessPostStatus.PUBLISHED)) {
            var post = communityPostRepository.findById(bp.getPostId()).orElse(null);
            if (post == null || post.getDeletedAt() != null || post.getStatus() == CommunityContentStatus.REMOVED) {
                bp.setStatus(BusinessPostStatus.REMOVED);
                businessPostRepository.save(bp);
                n++;
            }
        }
        return n;
    }
}
