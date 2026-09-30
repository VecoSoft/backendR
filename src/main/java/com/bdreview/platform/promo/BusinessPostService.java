package com.bdreview.platform.promo;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.catalog.MenuItem;
import com.bdreview.platform.catalog.MenuItemRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.CommunityContentStatus;
import com.bdreview.platform.community.CommunityPost;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.community.CommunityPostResponse;
import com.bdreview.platform.community.CommunityPostService;
import com.bdreview.platform.community.CommunityPostType;
import com.bdreview.platform.community.moderation.CommunityModerationService;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.community.settings.CommunitySettingsService;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.promo.PromoEnums.BusinessPostStatus;
import com.bdreview.platform.promo.PromoEnums.BusinessPostType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Posting as a business (V58). A business post is a community_post authored as the business plus
 * a business_post sidecar. Every rule is enforced here, server-side: ownership, promotion
 * suspension, the feature switch, the weekly limit, body length, banned categories, own-links
 * only, and that a linked offer / menu item / creative really belongs to this business.
 */
@Service
public class BusinessPostService {

    /** Non-offer, non-event posts stay "fresh" (and boostable) for this long. */
    static final Duration DEFAULT_LIFETIME = Duration.ofDays(30);
    static final String HOLD_REASON = "BUSINESS_POST";

    public record BusinessPostRequest(
            @NotNull BusinessPostType type,
            @Size(max = 150) String title,
            @Size(max = 5000) String body,
            UUID creativeId,
            UUID offerId,
            UUID menuItemId,
            Instant eventStart,
            Instant eventEnd,
            /** false = save as draft; true = submit (publish, or queue for review when approval is required). */
            boolean publish) {
    }

    private final BusinessPostRepository businessPostRepository;
    private final CommunityPostRepository communityPostRepository;
    private final CommunityPostService communityPostService;
    private final CommunitySettingsService settingsService;
    private final PromoAccess access;
    private final PromotionContentRules contentRules;
    private final OfferRepository offerRepository;
    private final MenuItemRepository menuItemRepository;
    private final PromoCreativeRepository creativeRepository;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbc;

    public BusinessPostService(BusinessPostRepository businessPostRepository, CommunityPostRepository communityPostRepository,
                               CommunityPostService communityPostService, CommunitySettingsService settingsService,
                               PromoAccess access, PromotionContentRules contentRules, OfferRepository offerRepository,
                               MenuItemRepository menuItemRepository, PromoCreativeRepository creativeRepository,
                               AuditLogService auditLogService, JdbcTemplate jdbc) {
        this.businessPostRepository = businessPostRepository;
        this.communityPostRepository = communityPostRepository;
        this.communityPostService = communityPostService;
        this.settingsService = settingsService;
        this.access = access;
        this.contentRules = contentRules;
        this.offerRepository = offerRepository;
        this.menuItemRepository = menuItemRepository;
        this.creativeRepository = creativeRepository;
        this.auditLogService = auditLogService;
        this.jdbc = jdbc;
    }

    // -----------------------------------------------------------------
    // Owner
    // -----------------------------------------------------------------

    @Transactional
    public CommunityPostResponse create(UUID userId, UUID businessId, BusinessPostRequest req) {
        CommunitySettings.Promotions settings = requireEnabled();
        Business business = access.requireCanPromote(userId, businessId);
        Links links = validate(business, req, settings);

        CommunityPost post = communityPostRepository.save(CommunityPost.builder()
                .authorUserId(userId)
                .authorBusinessId(businessId)
                .title(blankToNull(req.title()))
                .body(req.body() == null ? null : req.body().strip())
                .postType(CommunityPostType.DISCUSSION)
                .topic(settingsService.config().defaultTopicCode())
                .areaId(business.getArea() == null ? null : business.getArea().getId())
                .status(CommunityContentStatus.DRAFT)
                .build());
        BusinessPost bp = businessPostRepository.save(BusinessPost.builder()
                .postId(post.getId())
                .businessId(businessId)
                .authorUserId(userId)
                .type(req.type())
                .creativeId(req.creativeId())
                .offerId(links.offer == null ? null : links.offer.getId())
                .menuItemId(links.menuItem == null ? null : links.menuItem.getId())
                .eventStart(req.eventStart())
                .eventEnd(req.eventEnd())
                .expiresAt(expiryFor(req.type(), links.offer, req.eventEnd(), Instant.now()))
                .status(BusinessPostStatus.DRAFT)
                .build());
        if (req.publish()) {
            submit(bp, post, settings);
        }
        return communityPostService.getPost(post.getId(), userId);
    }

    @Transactional
    public CommunityPostResponse update(UUID userId, UUID postId, BusinessPostRequest req) {
        CommunitySettings.Promotions settings = requireEnabled();
        BusinessPost bp = requireOwnedPost(userId, postId);
        if (bp.getStatus() == BusinessPostStatus.REMOVED || bp.getStatus() == BusinessPostStatus.EXPIRED) {
            throw new BadRequestException("This post can no longer be edited.");
        }
        if (req.type() != bp.getType()) {
            throw new BadRequestException("A post's type can't be changed — create a new post instead.");
        }
        Business business = access.requireCanPromote(userId, bp.getBusinessId());
        Links links = validate(business, req, settings);
        CommunityPost post = requirePost(postId);
        post.setTitle(blankToNull(req.title()));
        post.setBody(req.body() == null ? null : req.body().strip());
        bp.setCreativeId(req.creativeId());
        bp.setOfferId(links.offer == null ? null : links.offer.getId());
        bp.setMenuItemId(links.menuItem == null ? null : links.menuItem.getId());
        bp.setEventStart(req.eventStart());
        bp.setEventEnd(req.eventEnd());
        bp.setExpiresAt(expiryFor(bp.getType(), links.offer, req.eventEnd(),
                bp.getPublishedAt() != null ? bp.getPublishedAt() : Instant.now()));
        boolean wasLive = bp.getStatus() == BusinessPostStatus.PUBLISHED;
        if (req.publish() || wasLive) {
            if (wasLive && settings.isRequireApprovalForBusinessPosts()) {
                // An edited live post goes back through review when approval is required.
                bp.setStatus(BusinessPostStatus.PENDING_REVIEW);
                post.setStatus(CommunityContentStatus.PENDING);
                post.setHoldReason(HOLD_REASON);
            } else if (!wasLive) {
                submit(bp, post, settings);
            }
        }
        communityPostRepository.save(post);
        businessPostRepository.save(bp);
        return communityPostService.getPost(postId, userId);
    }

    /** Submit a draft (or a rejected post after fixing it). */
    @Transactional
    public CommunityPostResponse publish(UUID userId, UUID postId) {
        CommunitySettings.Promotions settings = requireEnabled();
        BusinessPost bp = requireOwnedPost(userId, postId);
        if (bp.getStatus() != BusinessPostStatus.DRAFT && bp.getStatus() != BusinessPostStatus.REJECTED) {
            throw new BadRequestException("Only drafts and rejected posts can be submitted.");
        }
        Business business = access.requireCanPromote(userId, bp.getBusinessId());
        CommunityPost post = requirePost(postId);
        validate(business, new BusinessPostRequest(bp.getType(), post.getTitle(), post.getBody(), bp.getCreativeId(),
                bp.getOfferId(), bp.getMenuItemId(), bp.getEventStart(), bp.getEventEnd(), true), settings);
        submit(bp, post, settings);
        return communityPostService.getPost(postId, userId);
    }

    @Transactional
    public void delete(UUID userId, UUID postId) {
        BusinessPost bp = requireOwnedPost(userId, postId);
        communityPostRepository.softDelete(postId, Instant.now());
        bp.setStatus(BusinessPostStatus.REMOVED);
        businessPostRepository.save(bp);
    }

    /** "Interested" on an EVENT post — toggles. Returns the new count. */
    @Transactional
    public int toggleInterested(UUID userId, UUID postId) {
        BusinessPost bp = businessPostRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        if (bp.getType() != BusinessPostType.EVENT) {
            throw new BadRequestException("Only event posts take \"Interested\".");
        }
        if (effectiveStatus(bp, requirePost(postId)) != BusinessPostStatus.PUBLISHED) {
            throw new BadRequestException("This event is no longer open.");
        }
        int removed = jdbc.update("DELETE FROM business_post_interest WHERE post_id = ? AND user_id = ?", postId, userId);
        if (removed > 0) {
            businessPostRepository.adjustInterested(postId, -1);
        } else {
            jdbc.update("INSERT INTO business_post_interest (post_id, user_id) VALUES (?, ?)", postId, userId);
            businessPostRepository.adjustInterested(postId, 1);
        }
        return businessPostRepository.findById(postId).map(BusinessPost::getInterestedCount).orElse(0);
    }

    public List<BusinessPost> postsFor(UUID userId, UUID businessId) {
        access.requireOwned(userId, businessId);
        return businessPostRepository.findByBusinessIdOrderByCreatedAtDesc(businessId);
    }

    /** How many more posts this business may submit in the current rolling week. */
    public int remainingThisWeek(UUID businessId) {
        int limit = access.settings().getBusinessPostsPerWeek();
        long used = businessPostRepository.countSubmittedSince(businessId, Instant.now().minus(Duration.ofDays(7)),
                BusinessPostStatus.DRAFT);
        return (int) Math.max(0, limit - used);
    }

    // -----------------------------------------------------------------
    // Moderation (MODERATOR / ADMIN) — every action audit-logged
    // -----------------------------------------------------------------

    @Transactional
    public void approve(UUID postId, String reason) {
        CommunityModerationService.requireStaff();
        BusinessPost bp = businessPostRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Business post not found"));
        if (bp.getStatus() != BusinessPostStatus.PENDING_REVIEW) {
            throw new BadRequestException("Only posts waiting for review can be approved.");
        }
        CommunityPost post = requirePost(postId);
        String before = bp.getStatus().name();
        markPublished(bp, post);
        auditLogService.record("BUSINESS_POST", postId, "BUSINESS_POST_APPROVED", blankToDefault(reason, "Approved"),
                Map.of("status", before), Map.of("status", bp.getStatus().name()));
    }

    @Transactional
    public void reject(UUID postId, String reason) {
        CommunityModerationService.requireStaff();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required to reject a business post.");
        }
        BusinessPost bp = businessPostRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Business post not found"));
        String before = bp.getStatus().name();
        bp.setStatus(BusinessPostStatus.REJECTED);
        bp.setRejectionReason(reason.strip());
        businessPostRepository.save(bp);
        CommunityPost post = requirePost(postId);
        post.setStatus(CommunityContentStatus.REMOVED);
        post.setRemovedReason(reason.strip());
        post.setRemovedAt(Instant.now());
        communityPostRepository.save(post);
        auditLogService.record("BUSINESS_POST", postId, "BUSINESS_POST_REJECTED", reason.strip(),
                Map.of("status", before), Map.of("status", "REJECTED"));
    }

    /** Scheduled job / offer end: the post stays readable with an "Expired" label and leaves every sponsored slot. */
    @Transactional
    public boolean expire(UUID postId, String why) {
        BusinessPost bp = businessPostRepository.findById(postId).orElse(null);
        if (bp == null || bp.getStatus() == BusinessPostStatus.EXPIRED || bp.getStatus() == BusinessPostStatus.REMOVED
                || bp.getStatus() == BusinessPostStatus.REJECTED || bp.getStatus() == BusinessPostStatus.DRAFT) {
            return false;
        }
        String before = bp.getStatus().name();
        bp.setStatus(BusinessPostStatus.EXPIRED);
        businessPostRepository.save(bp);
        // A post that was still waiting for review has nothing to show — keep it out of the feed.
        if ("PENDING_REVIEW".equals(before)) {
            communityPostRepository.findById(postId).ifPresent(p -> {
                p.setStatus(CommunityContentStatus.REMOVED);
                p.setRemovedReason("Expired before review");
                communityPostRepository.save(p);
            });
        }
        auditLogService.recordSystem("BUSINESS_POST", postId, "BUSINESS_POST_EXPIRED", why, Map.of("status", before),
                Map.of("status", "EXPIRED"));
        return true;
    }

    /**
     * The promotion status the rest of the app should act on. Also absorbs actions taken through
     * the generic community moderation tools (approve / remove / restore a post there).
     */
    public static BusinessPostStatus effectiveStatus(BusinessPost bp, CommunityPost post) {
        if (post == null || post.getDeletedAt() != null) {
            return BusinessPostStatus.REMOVED;
        }
        BusinessPostStatus s = bp.getStatus();
        if (post.getStatus() == CommunityContentStatus.REMOVED || post.getStatus() == CommunityContentStatus.HIDDEN) {
            return s == BusinessPostStatus.REJECTED ? s : BusinessPostStatus.REMOVED;
        }
        if (s == BusinessPostStatus.PENDING_REVIEW && post.getStatus() == CommunityContentStatus.ACTIVE) {
            return BusinessPostStatus.PUBLISHED;
        }
        if (s == BusinessPostStatus.PUBLISHED && bp.getExpiresAt() != null && !bp.getExpiresAt().isAfter(Instant.now())) {
            return BusinessPostStatus.EXPIRED;
        }
        return s;
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private record Links(Offer offer, MenuItem menuItem) {
    }

    private CommunitySettings.Promotions requireEnabled() {
        CommunitySettings s = settingsService.settings();
        if (!s.getGeneral().isCommunityEnabled()) {
            throw new BadRequestException(s.getGeneral().getMaintenanceMessage());
        }
        if (s.getGeneral().isReadOnly()) {
            throw new ForbiddenException(s.getGeneral().getReadOnlyMessage());
        }
        if (!s.getPromotions().isBusinessPostsEnabled()) {
            throw new ForbiddenException("Business posts are turned off right now.");
        }
        return s.getPromotions();
    }

    private Links validate(Business business, BusinessPostRequest req, CommunitySettings.Promotions settings) {
        contentRules.checkBody(settings, req.body());
        contentRules.check(settings, business, req.title(), req.body());

        Offer offer = null;
        MenuItem menuItem = null;
        if (req.offerId() != null) {
            offer = offerRepository.findById(req.offerId())
                    .filter(o -> o.getBusinessId().equals(business.getId()))
                    .orElseThrow(() -> new BadRequestException("That offer doesn't belong to this business."));
            if (!offer.isCurrentlyActive()) {
                throw new BadRequestException("Only a live, approved offer can be promoted.");
            }
            if (offer.getMenuItemId() != null && req.menuItemId() == null) {
                menuItem = menuItemRepository.findById(offer.getMenuItemId()).orElse(null);
            }
        }
        if (req.menuItemId() != null) {
            menuItem = menuItemRepository.findById(req.menuItemId())
                    .filter(m -> m.getBusinessId().equals(business.getId()))
                    .orElseThrow(() -> new BadRequestException("That menu item doesn't belong to this business."));
        }
        if (req.creativeId() != null) {
            PromoCreative creative = creativeRepository.findById(req.creativeId())
                    .orElseThrow(() -> new BadRequestException("Creative not found."));
            if (!creative.getBusinessId().equals(business.getId())) {
                throw new ForbiddenException("That creative belongs to another business.");
            }
        }
        switch (req.type()) {
            case OFFER -> {
                if (offer == null) {
                    throw new BadRequestException("Pick the offer to promote.");
                }
            }
            case MENU_ITEM -> {
                if (menuItem == null) {
                    throw new BadRequestException("Pick the menu item to promote.");
                }
                if (!menuItem.isAvailable()) {
                    throw new BadRequestException("That menu item is marked unavailable.");
                }
            }
            case EVENT -> {
                if (req.eventStart() == null || req.eventEnd() == null) {
                    throw new BadRequestException("An event needs a start and end time.");
                }
                if (!req.eventEnd().isAfter(req.eventStart())) {
                    throw new BadRequestException("The event must end after it starts.");
                }
                if (req.eventEnd().isBefore(Instant.now())) {
                    throw new BadRequestException("That event has already ended.");
                }
            }
            default -> {
            }
        }
        return new Links(offer, menuItem);
    }

    private void submit(BusinessPost bp, CommunityPost post, CommunitySettings.Promotions settings) {
        if (remainingThisWeek(bp.getBusinessId()) <= 0) {
            throw new BadRequestException("You've reached this week's limit of " + settings.getBusinessPostsPerWeek()
                    + " business posts. Drafts are saved — submit again later.");
        }
        if (settings.isRequireApprovalForBusinessPosts()) {
            bp.setStatus(BusinessPostStatus.PENDING_REVIEW);
            bp.setRejectionReason(null);
            post.setStatus(CommunityContentStatus.PENDING);
            post.setHoldReason(HOLD_REASON);
            post.setRemovedReason(null);
            post.setRemovedAt(null);
            communityPostRepository.save(post);
            businessPostRepository.save(bp);
        } else {
            markPublished(bp, post);
        }
    }

    private void markPublished(BusinessPost bp, CommunityPost post) {
        Instant now = Instant.now();
        bp.setStatus(BusinessPostStatus.PUBLISHED);
        bp.setRejectionReason(null);
        bp.setPublishedAt(now);
        if (bp.getType() != BusinessPostType.OFFER && bp.getType() != BusinessPostType.EVENT) {
            bp.setExpiresAt(now.plus(DEFAULT_LIFETIME));
        }
        post.setStatus(CommunityContentStatus.ACTIVE);
        post.setHoldReason(null);
        post.setRemovedReason(null);
        post.setRemovedAt(null);
        communityPostRepository.save(post);
        businessPostRepository.save(bp);
    }

    private static Instant expiryFor(BusinessPostType type, Offer offer, Instant eventEnd, Instant from) {
        return switch (type) {
            case OFFER -> offer == null ? null : offer.getValidUntil();
            case EVENT -> eventEnd;
            default -> from.plus(DEFAULT_LIFETIME);
        };
    }

    private BusinessPost requireOwnedPost(UUID userId, UUID postId) {
        BusinessPost bp = businessPostRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Business post not found"));
        access.requireOwned(userId, bp.getBusinessId());
        return bp;
    }

    private CommunityPost requirePost(UUID postId) {
        return communityPostRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String blankToDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.strip();
    }
}
