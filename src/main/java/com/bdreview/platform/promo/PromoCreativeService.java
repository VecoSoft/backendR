package com.bdreview.platform.promo;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.catalog.MenuItem;
import com.bdreview.platform.catalog.MenuItemRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.gallery.BusinessPhoto;
import com.bdreview.platform.gallery.BusinessPhotoRepository;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.offer.OfferStatus;
import com.bdreview.platform.promo.PromoEnums.CreativeFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto Design Studio backend (V58): the owner's real data for the Studio, validated creative
 * saves, and the render model that both the live preview and the PNG renderer draw from.
 * Owners pick from their own data only — photos from their own listing, offers that are live,
 * menu items they list — and never type a price, a rating or offer terms.
 */
@Service
public class PromoCreativeService {

    public static final int HEADLINE_MAX = 40;
    public static final int SUBLINE_MAX = 80;
    public static final List<String> BRAND_SWATCHES = List.of("#C8102E", "#111827", "#0F766E", "#B45309", "#6D28D9", "#1D4ED8");

    public record CreativeData(
            @Size(max = HEADLINE_MAX) String headline,
            @Size(max = SUBLINE_MAX) String subline,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String accentColor,
            @Size(max = 2048) String photoUrl,
            UUID offerId,
            UUID menuItemId,
            @Size(max = 80) String eventTitle,
            Instant eventStart,
            Instant eventEnd,
            boolean showRating,
            boolean showQr,
            boolean showPrice) {
    }

    public record CreativeRequest(@NotBlank @Size(max = 40) String templateKey, @jakarta.validation.Valid CreativeData data) {
    }

    public record UploadTarget(String format, String objectKey, String putUrl) {
    }

    public record CreativeView(UUID id, UUID businessId, String templateKey, CreativeData data, String squareUrl,
                               String storyUrl, String ogUrl, Instant createdAt) {
    }

    public record SavedCreative(CreativeView creative, List<UploadTarget> uploads) {
    }

    public record RenderedRequest(@NotBlank String squareKey, @NotBlank String storyKey, @NotBlank String ogKey) {
    }

    public record TemplateView(String key, String name, List<String> supportedTypes, List<String> formats, String configJson) {
    }

    public record StudioData(
            UUID businessId, String businessName, String slug, String logoUrl, String areaName, String cityName,
            String categoryKind, BigDecimal averageRating, int reviewCount, PromoRenderModel.Quote bestQuote,
            List<String> photos, List<BusinessPostView.OfferRef> offers, List<BusinessPostView.MenuItemRef> menuItems,
            List<TemplateView> templates, List<String> swatches, String logoColor,
            int postsRemainingThisWeek, boolean captionAiEnabled, boolean boostsEnabled) {
    }

    private final PromoAccess access;
    private final PromoCreativeRepository creativeRepository;
    private final PromoTemplateRepository templateRepository;
    private final OfferRepository offerRepository;
    private final MenuItemRepository menuItemRepository;
    private final BusinessPhotoRepository photoRepository;
    private final BusinessRepository businessRepository;
    private final ObjectStorageClient storage;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final BusinessPostService businessPostService;
    private final PromotionContentRules contentRules;
    private final String frontendUrl;
    private final String storageBaseUrl;
    private final Map<String, String> logoColorCache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public PromoCreativeService(PromoAccess access, PromoCreativeRepository creativeRepository,
                                PromoTemplateRepository templateRepository, OfferRepository offerRepository,
                                MenuItemRepository menuItemRepository, BusinessPhotoRepository photoRepository,
                                BusinessRepository businessRepository, ObjectStorageClient storage, ObjectMapper objectMapper,
                                JdbcTemplate jdbc, BusinessPostService businessPostService,
                                PromotionContentRules contentRules,
                                @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl,
                                @Value("${app.storage.base-url}") String storageBaseUrl) {
        this.access = access;
        this.creativeRepository = creativeRepository;
        this.templateRepository = templateRepository;
        this.offerRepository = offerRepository;
        this.menuItemRepository = menuItemRepository;
        this.photoRepository = photoRepository;
        this.businessRepository = businessRepository;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.businessPostService = businessPostService;
        this.contentRules = contentRules;
        this.frontendUrl = frontendUrl.replaceAll("/$", "");
        this.storageBaseUrl = storageBaseUrl.replaceAll("/$", "");
    }

    // -----------------------------------------------------------------
    // Studio data
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public StudioData studio(UUID userId, UUID businessId) {
        access.requireOwned(userId, businessId);
        Business b = loadBusiness(businessId);
        Instant now = Instant.now();
        List<BusinessPostView.OfferRef> offers = offerRepository
                .findByBusinessIdAndStatusAndValidUntilAfterOrderByValidUntilAsc(businessId, OfferStatus.ACTIVE, now).stream()
                .filter(Offer::isCurrentlyActive)
                .map(o -> new BusinessPostView.OfferRef(o.getId(), o.getTitle(), o.getOfferType() == null ? null : o.getOfferType().name(),
                        o.getDiscountValue(), o.getOriginalPrice(), o.getOfferPrice(), o.getValidUntil(), true))
                .toList();
        List<BusinessPostView.MenuItemRef> items = menuItemRepository.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .filter(MenuItem::isAvailable)
                .map(m -> new BusinessPostView.MenuItemRef(m.getId(), m.getName(), m.getPrice(), m.getPriceText(), m.getPhotoUrl(),
                        m.isAvailable(), m.isOrderingEnabled()))
                .toList();
        List<TemplateView> templates = templateRepository.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(t -> new TemplateView(t.getKey(), t.getName(), t.getSupportedTypes(), t.getFormats(), t.getConfigJson()))
                .toList();
        return new StudioData(b.getId(), b.getName(), b.getSlug(), b.getLogoUrl(),
                b.getArea() == null ? null : b.getArea().getName(), b.getCity() == null ? null : b.getCity().getName(),
                b.getCategory() == null ? null : b.getCategory().getKind().name(),
                b.getReviewCount() > 0 ? b.getAverageRating() : null, b.getReviewCount(), bestQuote(businessId),
                new ArrayList<>(allowedPhotos(b)), offers, items, templates, BRAND_SWATCHES, logoColor(b.getLogoUrl()),
                businessPostService.remainingThisWeek(businessId), access.settings().isCaptionAiEnabled(),
                access.settings().isBoostsEnabled());
    }

    // -----------------------------------------------------------------
    // Render model — built live from the database every time
    // -----------------------------------------------------------------

    /** Studio preview for unsaved choices (owner only). */
    @Transactional(readOnly = true)
    public PromoRenderModel previewModel(UUID userId, UUID businessId, CreativeRequest req) {
        access.requireOwned(userId, businessId);
        Business b = loadBusiness(businessId);
        requireTemplate(req.templateKey(), false);
        CreativeData data = sanitize(b, req.data());
        return buildModel(b, req.templateKey(), data, null);
    }

    /** Public: a saved creative's model — what the PNG renderer and the share page draw. */
    @Transactional(readOnly = true)
    public PromoRenderModel modelFor(UUID creativeId) {
        PromoCreative c = creativeRepository.findById(creativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Creative not found"));
        Business b = loadBusiness(c.getBusinessId());
        return buildModel(b, c.getTemplateKey(), readData(c), c.getId());
    }

    PromoRenderModel buildModel(Business b, String templateKey, CreativeData d, UUID creativeId) {
        Offer offer = d.offerId() == null ? null : offerRepository.findById(d.offerId())
                .filter(o -> o.getBusinessId().equals(b.getId())).orElse(null);
        MenuItem item = d.menuItemId() == null ? null : menuItemRepository.findById(d.menuItemId())
                .filter(m -> m.getBusinessId().equals(b.getId())).orElse(null);
        if (item == null && offer != null && offer.getMenuItemId() != null) {
            item = menuItemRepository.findById(offer.getMenuItemId()).orElse(null);
        }
        boolean hasReviews = b.getReviewCount() > 0;
        String share = frontendUrl + "/business/" + b.getSlug() + (creativeId == null ? "" : "?ref=promo_" + creativeId);
        String photo = d.photoUrl() != null ? d.photoUrl()
                : item != null && item.getPhotoUrl() != null ? item.getPhotoUrl()
                : offer != null && offer.getImageUrl() != null ? offer.getImageUrl()
                : b.getCoverPhotoUrl();
        PromoRenderModel.Offer offerBlock = offer == null ? null : new PromoRenderModel.Offer(offer.getTitle(), offerHeadline(offer),
                offer.getOriginalPrice(), offer.getOfferPrice(), offer.getValidUntil(), offer.isCurrentlyActive());
        PromoRenderModel.MenuItem itemBlock = item == null ? null
                : new PromoRenderModel.MenuItem(item.getName(), item.getPrice(), item.getPriceText(), item.getPhotoUrl());
        PromoRenderModel.Event eventBlock = d.eventStart() == null ? null : new PromoRenderModel.Event(
                d.eventTitle() == null || d.eventTitle().isBlank() ? d.headline() : d.eventTitle(), d.eventStart(), d.eventEnd(),
                joinPlace(b));
        return new PromoRenderModel(templateKey, b.getName(), b.getLogoUrl(),
                b.getArea() == null ? null : b.getArea().getName(), b.getCity() == null ? null : b.getCity().getName(),
                hasReviews ? b.getAverageRating().setScale(1, RoundingMode.HALF_UP) : null, b.getReviewCount(),
                hasReviews ? bestQuote(b.getId()) : null,
                d.headline(), d.subline(), d.accentColor() == null ? BRAND_SWATCHES.get(0) : d.accentColor(), photo,
                d.showRating() && hasReviews, d.showQr(), d.showPrice(), offerBlock, itemBlock, eventBlock, share,
                offer != null && !offer.isCurrentlyActive());
    }

    /** The big line on OFFER_BOLD — derived from the offer's own type and value, never typed by the owner. */
    static String offerHeadline(Offer o) {
        if (o.getOfferType() == null) {
            return o.getTitle();
        }
        return switch (o.getOfferType()) {
            case PERCENTAGE_DISCOUNT -> o.getDiscountValue() == null ? o.getTitle()
                    : o.getDiscountValue().stripTrailingZeros().toPlainString() + "% OFF";
            case FIXED_AMOUNT_DISCOUNT -> o.getDiscountValue() == null ? o.getTitle()
                    : "৳" + o.getDiscountValue().stripTrailingZeros().toPlainString() + " OFF";
            case BUY_ONE_GET_ONE -> "BUY 1 GET 1";
            case COMBO_DEAL -> "COMBO DEAL";
            case FREE_ITEM -> "FREE ITEM";
            default -> o.getTitle();
        };
    }

    private static String joinPlace(Business b) {
        String area = b.getArea() == null ? null : b.getArea().getName();
        String city = b.getCity() == null ? null : b.getCity().getName();
        return area == null ? city : city == null ? area : area + ", " + city;
    }

    /** A real, recommended ≥4★ review, trimmed; attributed by first name only. */
    PromoRenderModel.Quote bestQuote(UUID businessId) {
        List<PromoRenderModel.Quote> rows = jdbc.query("""
                SELECT r.content, u.name, r.rating FROM review r JOIN app_user u ON u.id = r.user_id
                WHERE r.business_id = ? AND r.deleted_at IS NULL AND r.visibility_status = 'RECOMMENDED'
                  AND r.rating >= 4 AND char_length(trim(r.content)) BETWEEN 15 AND 400
                ORDER BY r.useful_count DESC, r.rating DESC, char_length(r.content) ASC, r.created_at DESC
                LIMIT 1
                """, (rs, n) -> new PromoRenderModel.Quote(shortQuote(rs.getString(1)), firstName(rs.getString(2)), rs.getInt(3)),
                businessId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String shortQuote(String content) {
        String c = content.strip().replaceAll("\\s+", " ");
        if (c.length() <= 120) {
            return c;
        }
        int cut = c.lastIndexOf(' ', 117);
        return c.substring(0, cut > 40 ? cut : 117).strip() + "…";
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "A customer";
        }
        return name.strip().split("\\s+")[0];
    }

    // -----------------------------------------------------------------
    // Save + rendered outputs
    // -----------------------------------------------------------------

    @Transactional
    public SavedCreative save(UUID userId, UUID businessId, CreativeRequest req) {
        access.requireCanPromote(userId, businessId);
        Business b = loadBusiness(businessId);
        requireTemplate(req.templateKey(), true);
        CreativeData data = sanitize(b, req.data());
        PromoCreative c = creativeRepository.save(PromoCreative.builder()
                .businessId(businessId)
                .templateKey(req.templateKey())
                .dataJson(writeData(data))
                .createdBy(userId)
                .build());
        return new SavedCreative(toView(c), uploadTargets(c));
    }

    /** Re-save the owner's choices on an existing creative; outputs are re-rendered by the client afterwards. */
    @Transactional
    public SavedCreative update(UUID userId, UUID creativeId, CreativeRequest req) {
        PromoCreative c = creativeRepository.findById(creativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Creative not found"));
        access.requireCanPromote(userId, c.getBusinessId());
        Business b = loadBusiness(c.getBusinessId());
        requireTemplate(req.templateKey(), true);
        c.setTemplateKey(req.templateKey());
        c.setDataJson(writeData(sanitize(b, req.data())));
        creativeRepository.save(c);
        return new SavedCreative(toView(c), uploadTargets(c));
    }

    @Transactional
    public CreativeView markRendered(UUID userId, UUID creativeId, RenderedRequest req) {
        PromoCreative c = creativeRepository.findById(creativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Creative not found"));
        access.requireOwned(userId, c.getBusinessId());
        String prefix = "promo/" + c.getBusinessId() + "/";
        for (String key : List.of(req.squareKey(), req.storyKey(), req.ogKey())) {
            if (!key.startsWith(prefix) || key.contains("..") || !key.endsWith(".png")) {
                throw new BadRequestException("Rendered image keys must be this business's promo uploads.");
            }
        }
        c.setSquareUrl(storage.cdnUrlFor(req.squareKey()));
        c.setStoryUrl(storage.cdnUrlFor(req.storyKey()));
        c.setOgUrl(storage.cdnUrlFor(req.ogKey()));
        return toView(creativeRepository.save(c));
    }

    public List<CreativeView> recent(UUID userId, UUID businessId) {
        access.requireOwned(userId, businessId);
        return creativeRepository.findTop20ByBusinessIdOrderByCreatedAtDesc(businessId).stream().map(this::toView).toList();
    }

    public CreativeView get(UUID userId, UUID creativeId) {
        PromoCreative c = creativeRepository.findById(creativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Creative not found"));
        access.requireOwned(userId, c.getBusinessId());
        return toView(c);
    }

    private List<UploadTarget> uploadTargets(PromoCreative c) {
        List<UploadTarget> out = new ArrayList<>();
        long version = System.currentTimeMillis();
        for (CreativeFormat f : CreativeFormat.values()) {
            String key = "promo/" + c.getBusinessId() + "/" + c.getId() + "-" + f.name().toLowerCase(Locale.ROOT) + "-" + version + ".png";
            out.add(new UploadTarget(f.name(), key, storage.presignPutUrl(key)));
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    private CreativeData sanitize(Business b, CreativeData d) {
        if (d == null) {
            throw new BadRequestException("Creative data is required.");
        }
        String headline = trimTo(d.headline(), HEADLINE_MAX);
        String subline = trimTo(d.subline(), SUBLINE_MAX);
        // Banned categories apply to creative text too — the owner types the headline/subline.
        contentRules.check(access.settings(), b, headline, subline, d.eventTitle());
        if (d.photoUrl() != null && !d.photoUrl().isBlank() && !allowedPhotos(b).contains(d.photoUrl())) {
            throw new BadRequestException("Pick a photo from your own listing, menu or offers.");
        }
        if (d.offerId() != null) {
            Offer o = offerRepository.findById(d.offerId()).filter(x -> x.getBusinessId().equals(b.getId()))
                    .orElseThrow(() -> new BadRequestException("That offer doesn't belong to this business."));
            if (!o.isCurrentlyActive()) {
                throw new BadRequestException("Only a live offer can be used in a creative.");
            }
        }
        if (d.menuItemId() != null) {
            menuItemRepository.findById(d.menuItemId()).filter(m -> m.getBusinessId().equals(b.getId()))
                    .orElseThrow(() -> new BadRequestException("That menu item doesn't belong to this business."));
        }
        if (d.eventStart() != null && d.eventEnd() != null && !d.eventEnd().isAfter(d.eventStart())) {
            throw new BadRequestException("The event must end after it starts.");
        }
        return new CreativeData(headline, subline, d.accentColor() == null ? null : d.accentColor().toUpperCase(Locale.ROOT),
                d.photoUrl() == null || d.photoUrl().isBlank() ? null : d.photoUrl(), d.offerId(), d.menuItemId(),
                trimTo(d.eventTitle(), 80), d.eventStart(), d.eventEnd(), d.showRating(), d.showQr(), d.showPrice());
    }

    /** Photos an owner may put on a creative: their own cover/logo, gallery, menu item and offer photos. */
    Set<String> allowedPhotos(Business b) {
        Set<String> out = new LinkedHashSet<>();
        if (b.getCoverPhotoUrl() != null) {
            out.add(b.getCoverPhotoUrl());
        }
        photoRepository.findByBusinessIdOrderBySortOrderAsc(b.getId()).stream().map(BusinessPhoto::getUrl).forEach(out::add);
        menuItemRepository.findByBusinessIdOrderBySortOrderAsc(b.getId()).stream()
                .map(MenuItem::getPhotoUrl).filter(Objects::nonNull).forEach(out::add);
        offerRepository.findByBusinessIdAndStatusAndValidUntilAfterOrderByValidUntilAsc(b.getId(), OfferStatus.ACTIVE, Instant.now())
                .stream().map(Offer::getImageUrl).filter(Objects::nonNull).forEach(out::add);
        if (b.getLogoUrl() != null) {
            out.add(b.getLogoUrl());
        }
        return out;
    }

    private PromoTemplate requireTemplate(String key, boolean mustBeActive) {
        PromoTemplate t = templateRepository.findByKey(key)
                .orElseThrow(() -> new BadRequestException("Unknown template: " + key));
        if (mustBeActive && !t.isActive()) {
            throw new BadRequestException("That template is turned off.");
        }
        return t;
    }

    private Business loadBusiness(UUID businessId) {
        return businessRepository.findAllByIdInWithPlace(List.of(businessId)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
    }

    private CreativeView toView(PromoCreative c) {
        return new CreativeView(c.getId(), c.getBusinessId(), c.getTemplateKey(), readData(c), c.getSquareUrl(),
                c.getStoryUrl(), c.getOgUrl(), c.getCreatedAt());
    }

    CreativeData readData(PromoCreative c) {
        try {
            return objectMapper.readValue(c.getDataJson(), CreativeData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable creative data for " + c.getId(), e);
        }
    }

    private String writeData(CreativeData d) {
        try {
            return objectMapper.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Creative data could not be saved.");
        }
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.codePointCount(0, t.length()) > max ? t.substring(0, t.offsetByCodePoints(0, max)) : t;
    }

    // -----------------------------------------------------------------
    // Logo colour — "extracted dominant color from the logo"
    // -----------------------------------------------------------------

    /** Dominant, reasonably saturated colour of the logo as #RRGGBB; null when there's no readable logo. */
    String logoColor(String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank()) {
            return null;
        }
        return logoColorCache.computeIfAbsent(logoUrl, url -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(readImage(url)));
                return img == null ? "" : dominantColor(img);
            } catch (Exception e) {
                return "";
            }
        }).transform(s -> s.isEmpty() ? null : s);
    }

    private byte[] readImage(String url) throws Exception {
        String own = storageBaseUrl + "/api/v1/storage/files/";
        if (url.startsWith(own)) {
            return storage.getObject(url.substring(own.length()));
        }
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            return in.readNBytes(3 * 1024 * 1024);
        }
    }

    static String dominantColor(BufferedImage img) {
        Map<Integer, int[]> buckets = new HashMap<>();
        int stepX = Math.max(1, img.getWidth() / 64);
        int stepY = Math.max(1, img.getHeight() / 64);
        for (int y = 0; y < img.getHeight(); y += stepY) {
            for (int x = 0; x < img.getWidth(); x += stepX) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, bl = argb & 0xff;
                int max = Math.max(r, Math.max(g, bl)), min = Math.min(r, Math.min(g, bl));
                // Skip transparent, near-white, near-black and grey pixels — backgrounds, not brand colour.
                if (a < 128 || max > 240 && min > 225 || max < 30 || max - min < 40) {
                    continue;
                }
                int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (bl >> 4);
                int[] acc = buckets.computeIfAbsent(key, k -> new int[4]);
                acc[0] += r;
                acc[1] += g;
                acc[2] += bl;
                acc[3]++;
            }
        }
        return buckets.values().stream().max(Comparator.comparingInt(v -> v[3]))
                .map(v -> String.format("#%02X%02X%02X", v[0] / v[3], v[1] / v[3], v[2] / v[3]))
                .orElse("");
    }
}
