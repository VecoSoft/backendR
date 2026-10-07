package com.bdreview.platform.photomod;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.features.PlatformSettingStore;
import com.bdreview.platform.gallery.BusinessPhoto;
import com.bdreview.platform.gallery.BusinessPhotoRepository;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Photo moderation (V63). While "Require approval for new photos" is on, every new business
 * gallery photo, cover, logo, menu item photo, community post image and review photo is held as
 * PENDING and is never returned by a public API until an ADMIN approves it:
 * <ul>
 *   <li>row-based sources (gallery / post / review) are saved with {@code moderation_status =
 *       'PENDING'} and every public read filters on APPROVED;</li>
 *   <li>single-field sources (cover / logo / menu item) keep their current live value; the new URL
 *       is copied into the field only on approval.</li>
 * </ul>
 * A pending photo's storage object is served only to its uploader (and admins); a rejected or
 * admin-deleted one stops being served at once (see {@link #canServe}). The uploader sees their own pending/rejected photos through
 * {@code GET /api/v1/photos/mine} (and the owner's own gallery read).
 */
@Service
public class PhotoModerationService {

    public static final String SETTING_KEY = "PHOTO_APPROVAL_REQUIRED";
    private static final String STORAGE_PATH = "/api/v1/storage/files/";

    private final PhotoModerationRepository repository;
    private final BusinessPhotoRepository businessPhotoRepository;
    private final PlatformSettingStore settings;
    private final JdbcTemplate jdbc;
    private final AuditLogService auditLogService;
    private final boolean approvalRequiredDefault;

    public PhotoModerationService(PhotoModerationRepository repository,
                                  BusinessPhotoRepository businessPhotoRepository,
                                  PlatformSettingStore settings,
                                  JdbcTemplate jdbc,
                                  AuditLogService auditLogService,
                                  @Value("${photos.approval-required:true}") boolean approvalRequiredDefault) {
        this.repository = repository;
        this.businessPhotoRepository = businessPhotoRepository;
        this.settings = settings;
        this.jdbc = jdbc;
        this.auditLogService = auditLogService;
        this.approvalRequiredDefault = approvalRequiredDefault;
    }

    // -----------------------------------------------------------------
    // Setting
    // -----------------------------------------------------------------

    public boolean approvalRequired() {
        return settings.get(SETTING_KEY).map(PlatformSettingStore.Row::enabled).orElse(approvalRequiredDefault);
    }

    public boolean approvalRequiredDefault() {
        return approvalRequiredDefault;
    }

    @Transactional
    public void setApprovalRequired(boolean required, String reason) {
        String why = requireReason(reason);
        boolean before = approvalRequired();
        settings.put(SETTING_KEY, required, null, CurrentUser.idOrNull());
        auditLogService.record("PLATFORM_SETTING", null, "PHOTO_APPROVAL_SETTING", why,
                Map.of("approvalRequired", before), Map.of("approvalRequired", required));
    }

    // -----------------------------------------------------------------
    // Upload-side hooks (called by the owning services)
    // -----------------------------------------------------------------

    /**
     * Status for a new photo row of a row-based source. A URL already decided for this source
     * keeps its decision (e.g. a post edit re-saving the same photos); otherwise PENDING (queued)
     * while approval is required, else APPROVED.
     */
    @Transactional
    public PhotoStatus admitRow(PhotoSource source, UUID sourceId, UUID businessId, UUID uploaderId, String url) {
        Optional<PhotoModeration> decided = latestLive(source, sourceId, url);
        if (decided.isPresent()) {
            return decided.get().getStatus() == PhotoStatus.PENDING ? PhotoStatus.PENDING
                    : decided.get().getStatus() == PhotoStatus.APPROVED ? PhotoStatus.APPROVED : PhotoStatus.REJECTED;
        }
        if (!approvalRequired()) {
            return PhotoStatus.APPROVED;
        }
        queue(source, sourceId, businessId, uploaderId, url);
        return PhotoStatus.PENDING;
    }

    /**
     * For single-field sources: returns the value to store in the live field right now. Removing
     * the photo (blank) or keeping the current one applies immediately; a new URL is queued and
     * the current value stays live until it is approved. A newer upload supersedes an older
     * pending one for the same field.
     */
    @Transactional
    public String admitField(PhotoSource source, UUID sourceId, UUID businessId, UUID uploaderId,
                             String currentUrl, String requestedUrl) {
        String requested = requestedUrl == null || requestedUrl.isBlank() ? null : requestedUrl.trim();
        if (requested == null) {
            withdrawPending(source, sourceId, List.of());
            return null;
        }
        if (requested.equals(currentUrl)) {
            return currentUrl;
        }
        Optional<PhotoModeration> decided = latestLive(source, sourceId, requested);
        if (decided.isPresent() && decided.get().getStatus() == PhotoStatus.APPROVED) {
            withdrawPending(source, sourceId, List.of());
            return requested;
        }
        if (decided.isPresent() && decided.get().getStatus() == PhotoStatus.PENDING) {
            return currentUrl;
        }
        if (decided.isPresent()) {
            // Rejected before — never goes live; the uploader sees the rejection via /photos/mine.
            return currentUrl;
        }
        withdrawPending(source, sourceId, List.of());
        if (!approvalRequired()) {
            return requested;
        }
        queue(source, sourceId, businessId, uploaderId, requested);
        return currentUrl;
    }

    /** Uploader replaced/removed photos before review: PENDING entries for this source not in {@code keptUrls} are withdrawn. */
    @Transactional
    public void withdrawPending(PhotoSource source, UUID sourceId, Collection<String> keptUrls) {
        for (PhotoModeration p : repository.findBySourceTypeAndSourceIdAndStatus(source, sourceId, PhotoStatus.PENDING)) {
            if (!keptUrls.contains(p.getUrl())) {
                p.setStatus(PhotoStatus.WITHDRAWN);
                repository.save(p);
            }
        }
    }

    // -----------------------------------------------------------------
    // Admin decisions
    // -----------------------------------------------------------------

    public Page<PhotoModeration> queue(PhotoStatus status, PhotoSource source, int page, int size) {
        return repository.queue(status == null ? PhotoStatus.PENDING : status, source, PageRequest.of(page, size));
    }

    public long pendingCount() {
        return repository.countByStatus(PhotoStatus.PENDING);
    }

    public PhotoModeration get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Photo not found"));
    }

    @Transactional
    public void approve(UUID id, String reason) {
        decide(get(id), PhotoStatus.APPROVED, requireReason(reason));
    }

    @Transactional
    public void reject(UUID id, String reason) {
        decide(get(id), PhotoStatus.REJECTED, requireReason(reason));
    }

    /** Applies one decision to many photos; returns how many changed. */
    @Transactional
    public int decideAll(Collection<UUID> ids, PhotoStatus decision, String reason) {
        if (decision != PhotoStatus.APPROVED && decision != PhotoStatus.REJECTED) {
            throw new BadRequestException("Unknown action.");
        }
        if (ids == null || ids.isEmpty()) {
            throw new BadRequestException("Select at least one photo.");
        }
        String why = requireReason(reason);
        int changed = 0;
        for (PhotoModeration p : repository.findAllById(ids)) {
            if (p.getStatus() != decision) {
                decide(p, decision, why);
                changed++;
            }
        }
        return changed;
    }

    private void decide(PhotoModeration p, PhotoStatus decision, String reason) {
        if (p.getStatus() == PhotoStatus.WITHDRAWN || p.getStatus() == PhotoStatus.DELETED) {
            throw new BadRequestException("This photo was " + p.getStatus().name().toLowerCase() + " and can't be reviewed any more.");
        }
        PhotoStatus before = p.getStatus();
        p.setStatus(decision);
        p.setReason(reason);
        p.setReviewedBy(CurrentUser.idOrNull());
        p.setReviewedAt(Instant.now());
        repository.save(p);
        applyToSource(p, decision);
        if (decision == PhotoStatus.REJECTED && templates != null && p.getSourceType() != PhotoSource.HERO
                && p.getSourceType() != PhotoSource.SUPPORT) {
            // V67: the uploader learns why (editable text, System → Notifications → Templates).
            templates.notify(p.getUploaderUserId(), com.bdreview.platform.notification.NotificationTemplateService.Key.PHOTO_REJECTED,
                    Map.of("photoType", p.getSourceType().label().toLowerCase(), "reason", reason),
                    com.bdreview.platform.notification.NotificationType.ADMIN_NOTICE, "PHOTO", p.getId());
        }
        auditLogService.record("PHOTO", p.getId(), decision == PhotoStatus.APPROVED ? "PHOTO_APPROVED" : "PHOTO_REJECTED",
                reason, Map.of("status", before.name(), "source", p.getSourceType().name(), "url", p.getUrl()),
                Map.of("status", decision.name()));
    }

    /** Mirrors a decision onto the live data: row status, or the single field (set on approve, cleared on reject). */
    private void applyToSource(PhotoModeration p, PhotoStatus decision) {
        String rowStatus = decision == PhotoStatus.APPROVED ? "APPROVED" : "REJECTED";
        switch (p.getSourceType()) {
            case BUSINESS_PHOTO -> jdbc.update("UPDATE business_photo SET moderation_status = ? WHERE business_id = ? AND url = ?",
                    rowStatus, p.getSourceId(), p.getUrl());
            case POST -> jdbc.update("UPDATE community_post_photo SET moderation_status = ? WHERE post_id = ? AND url = ?",
                    rowStatus, p.getSourceId(), p.getUrl());
            case REVIEW -> jdbc.update("UPDATE review_photo SET moderation_status = ? WHERE review_id = ? AND url = ?",
                    rowStatus, p.getSourceId(), p.getUrl());
            case COVER -> applyField("business", "cover_photo_url", p, decision);
            case LOGO -> applyField("business", "logo_url", p, decision);
            case MENU_ITEM -> applyField("business_menu_item", "photo_url", p, decision);
            case HERO -> applyHero(p, decision);
            case SUPPORT -> {
                if (decision == PhotoStatus.REJECTED) {
                    jdbc.update("UPDATE support_ticket SET screenshot_url = NULL WHERE id = ? AND screenshot_url = ?", p.getSourceId(), p.getUrl());
                }
            }
        }
    }

    /** V65: the homepage hero lives in the HOMEPAGE admin config document, not a table column. */
    private void applyHero(PhotoModeration p, PhotoStatus decision) {
        if (adminConfig == null) {
            return;
        }
        var home = adminConfig.homepage();
        if (decision == PhotoStatus.APPROVED) {
            home.setHeroImageUrl(p.getUrl());
        } else if (p.getUrl().equals(home.getHeroImageUrl())) {
            home.setHeroImageUrl(null);
        } else {
            return;
        }
        adminConfig.writeHomepageSystem(home);
    }

    private com.bdreview.platform.adminconfig.AdminConfigService adminConfig;

    /** V67 editable notification texts (System → Notifications → Templates) — setter-injected. */
    private com.bdreview.platform.notification.NotificationTemplateService templates;

    @org.springframework.beans.factory.annotation.Autowired
    void setTemplates(com.bdreview.platform.notification.NotificationTemplateService templates) {
        this.templates = templates;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setAdminConfig(com.bdreview.platform.adminconfig.AdminConfigService adminConfig) {
        this.adminConfig = adminConfig;
    }

    private void applyField(String table, String column, PhotoModeration p, PhotoStatus decision) {
        if (decision == PhotoStatus.APPROVED) {
            jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", p.getUrl(), p.getSourceId());
        } else {
            jdbc.update("UPDATE " + table + " SET " + column + " = NULL WHERE id = ? AND " + column + " = ?",
                    p.getSourceId(), p.getUrl());
        }
    }

    // -----------------------------------------------------------------
    // Admin business-page actions
    // -----------------------------------------------------------------

    /** Status of each gallery photo for the admin business page (no queue row = approved before V63). */
    public List<BusinessPhoto> galleryWithStatus(UUID businessId) {
        return businessPhotoRepository.findByBusinessIdOrderBySortOrderAsc(businessId);
    }

    public List<PhotoModeration> pendingForBusiness(UUID businessId) {
        return repository.findByBusinessIdAndStatusOrderByCreatedAtDesc(businessId, PhotoStatus.PENDING);
    }

    /** Removes a gallery photo; its file stops being served. */
    @Transactional
    public void deleteBusinessPhoto(UUID businessId, UUID photoId, String reason) {
        String why = requireReason(reason);
        BusinessPhoto photo = businessPhotoRepository.findById(photoId)
                .filter(p -> p.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found"));
        businessPhotoRepository.delete(photo);
        PhotoModeration entry = latestLive(PhotoSource.BUSINESS_PHOTO, businessId, photo.getUrl())
                .orElseGet(() -> PhotoModeration.builder()
                        .sourceType(PhotoSource.BUSINESS_PHOTO).sourceId(businessId).businessId(businessId)
                        .url(photo.getUrl()).objectKey(objectKeyOf(photo.getUrl())).build());
        String before = photo.getModerationStatus();
        entry.setStatus(PhotoStatus.DELETED);
        entry.setReason(why);
        entry.setReviewedBy(CurrentUser.idOrNull());
        entry.setReviewedAt(Instant.now());
        repository.save(entry);
        auditLogService.record("BUSINESS", businessId, "BUSINESS_PHOTO_DELETED", why,
                Map.of("photoId", photoId.toString(), "url", photo.getUrl(), "status", before), null);
    }

    /** Makes a gallery photo the cover (approving it too if it was still pending). */
    @Transactional
    public void setCover(UUID businessId, UUID photoId, String reason) {
        String why = requireReason(reason);
        BusinessPhoto photo = businessPhotoRepository.findById(photoId)
                .filter(p -> p.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found"));
        if ("REJECTED".equals(photo.getModerationStatus())) {
            throw new BadRequestException("A rejected photo can't be the cover.");
        }
        if ("PENDING".equals(photo.getModerationStatus())) {
            latestLive(PhotoSource.BUSINESS_PHOTO, businessId, photo.getUrl())
                    .ifPresent(p -> decide(p, PhotoStatus.APPROVED, "Approved by setting it as the cover: " + why));
        }
        String before = jdbc.queryForObject("SELECT cover_photo_url FROM business WHERE id = ?", String.class, businessId);
        jdbc.update("UPDATE business SET cover_photo_url = ? WHERE id = ?", photo.getUrl(), businessId);
        withdrawPending(PhotoSource.COVER, businessId, List.of());
        auditLogService.record("BUSINESS", businessId, "BUSINESS_COVER_SET", why,
                java.util.Collections.singletonMap("coverPhotoUrl", before), Map.of("coverPhotoUrl", photo.getUrl()));
    }

    // -----------------------------------------------------------------
    // Reads for the uploader / storage
    // -----------------------------------------------------------------

    /** The caller's pending photos and recent (30 days) rejections. */
    public List<PhotoModeration> mine(UUID uploaderId) {
        return repository.mine(uploaderId, List.of(PhotoStatus.PENDING, PhotoStatus.REJECTED),
                Instant.now().minus(30, ChronoUnit.DAYS));
    }

    /**
     * Whether the public storage URL may serve this object to {@code viewerId} (null = anonymous):
     * <ul>
     *   <li>no queue row (pre-V63 photo, or a file that isn't a moderated photo) or an APPROVED row → anyone;</li>
     *   <li>REJECTED / DELETED → nobody (admins look at it through the admin panel's proxy);</li>
     *   <li>PENDING / WITHDRAWN → only the uploader or an ADMIN token, so "Waiting for review"
     *       thumbnails still render for the person who uploaded them.</li>
     * </ul>
     */
    public boolean canServe(String objectKey, UUID viewerId, boolean viewerIsAdmin) {
        String key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        List<PhotoModeration> rows = repository.findByObjectKey(key);
        if (key.startsWith("support/")) {
            // V67: support screenshots are private from the moment they're uploaded.
            return viewerIsAdmin || (viewerId != null && rows.stream().anyMatch(p ->
                    viewerId.equals(p.getUploaderUserId()) && p.getStatus() != PhotoStatus.REJECTED && p.getStatus() != PhotoStatus.DELETED));
        }
        if (rows.isEmpty()) {
            return true;
        }
        if (rows.stream().anyMatch(p -> p.getStatus() == PhotoStatus.REJECTED || p.getStatus() == PhotoStatus.DELETED)) {
            return false;
        }
        if (rows.stream().anyMatch(p -> p.getSourceType() == PhotoSource.SUPPORT)) {
            // V67: support screenshots stay private even once approved.
            return viewerIsAdmin || (viewerId != null && rows.stream().anyMatch(p -> viewerId.equals(p.getUploaderUserId())));
        }
        if (rows.stream().anyMatch(p -> p.getStatus() == PhotoStatus.APPROVED)) {
            return true;
        }
        return viewerIsAdmin || (viewerId != null && rows.stream().anyMatch(p -> viewerId.equals(p.getUploaderUserId())));
    }

    /** V67: a support screenshot is always queued (private, regardless of the approval setting). */
    @Transactional
    public void admitSupportScreenshot(UUID ticketId, UUID uploaderId, String url) {
        queue(PhotoSource.SUPPORT, ticketId, null, uploaderId, url);
    }

    public static String objectKeyOf(String url) {
        if (url == null) {
            return null;
        }
        int i = url.indexOf(STORAGE_PATH);
        if (i < 0) {
            return null;
        }
        String key = url.substring(i + STORAGE_PATH.length());
        int q = key.indexOf('?');
        return q >= 0 ? key.substring(0, q) : key;
    }

    // -----------------------------------------------------------------

    private void queue(PhotoSource source, UUID sourceId, UUID businessId, UUID uploaderId, String url) {
        repository.save(PhotoModeration.builder()
                .sourceType(source)
                .sourceId(sourceId)
                .businessId(businessId)
                .url(url)
                .objectKey(objectKeyOf(url))
                .uploaderUserId(uploaderId)
                .status(PhotoStatus.PENDING)
                .build());
    }

    /** The most recent decision for this exact photo, ignoring withdrawn/deleted entries. */
    private Optional<PhotoModeration> latestLive(PhotoSource source, UUID sourceId, String url) {
        return repository.findBySourceTypeAndSourceIdAndUrlOrderByCreatedAtDesc(source, sourceId, url).stream()
                .filter(p -> p.getStatus() != PhotoStatus.WITHDRAWN && p.getStatus() != PhotoStatus.DELETED)
                .findFirst();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 1000) {
            throw new BadRequestException("The reason can be at most 1000 characters.");
        }
        return trimmed;
    }
}
