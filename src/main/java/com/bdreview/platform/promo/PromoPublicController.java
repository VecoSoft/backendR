package com.bdreview.platform.promo;

import com.bdreview.platform.business.BusinessResponse;
import com.bdreview.platform.business.BusinessService;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.CommunityContentStatus;
import com.bdreview.platform.community.CommunityPostResponse;
import com.bdreview.platform.community.CommunityPostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public promotion surface (V58): share pages (/p/&lt;postId&gt;), creative render data (read by
 * the Next.js PNG renderer and the share page), the home "Featured nearby" carousel, and the
 * analytics beacon. Nothing here exposes owner-only data (rejection reasons, payment details).
 */
@RestController
@RequestMapping("/api/v1/promo/public")
public class PromoPublicController {

    private static final int MAX_BEACON_BYTES = 32 * 1024;

    public record SharePayload(CommunityPostResponse post, BusinessResponse business, PromoRenderModel creative, String shareUrl) {
    }

    private final CommunityPostService communityPostService;
    private final BusinessPostRepository businessPostRepository;
    private final BusinessService businessService;
    private final PromoCreativeService creativeService;
    private final SponsoredService sponsoredService;
    private final PromoEventService eventService;
    private final ObjectMapper objectMapper;
    private final String frontendUrl;

    public PromoPublicController(CommunityPostService communityPostService, BusinessPostRepository businessPostRepository,
                                 BusinessService businessService, PromoCreativeService creativeService,
                                 SponsoredService sponsoredService, PromoEventService eventService, ObjectMapper objectMapper,
                                 @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.communityPostService = communityPostService;
        this.businessPostRepository = businessPostRepository;
        this.businessService = businessService;
        this.creativeService = creativeService;
        this.sponsoredService = sponsoredService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.frontendUrl = frontendUrl.replaceAll("/$", "");
    }

    /** Live render data for a saved creative — facts re-read from the database on every call. */
    @GetMapping("/creatives/{creativeId}/render-model")
    public ResponseEntity<PromoRenderModel> renderModel(@PathVariable UUID creativeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(creativeService.modelFor(creativeId));
    }

    /** Share page payload. Only published (or expired) business posts are shareable. */
    @GetMapping("/posts/{postId}/share")
    public SharePayload share(@PathVariable UUID postId) {
        BusinessPost bp = businessPostRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        CommunityPostResponse post = communityPostService.getPost(postId, CurrentUser.idOrNull());
        if (post.status() != CommunityContentStatus.ACTIVE) {
            throw new ResourceNotFoundException("Post not found");
        }
        BusinessResponse business = businessService.listingResponsesByIds(List.of(bp.getBusinessId())).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        PromoRenderModel creative = bp.getCreativeId() == null ? null : creativeService.modelFor(bp.getCreativeId());
        return new SharePayload(post, business, creative, frontendUrl + "/p/" + postId);
    }

    @GetMapping("/featured-nearby")
    public List<SponsoredService.SponsoredBusiness> featuredNearby(
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestHeader(value = "X-Promo-Session", required = false) String session) {
        boolean validCoords = lat != null && lng != null && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
        return sponsoredService.featuredNearby(new SponsoredService.Viewer(areaId, validCoords ? lat : null,
                validCoords ? lng : null, session));
    }

    /**
     * Batched analytics beacon. Accepts text/plain as well as JSON because navigator.sendBeacon
     * can only send CORS-safelisted content types cross-origin. The session id may come in the
     * body (sendBeacon can't set headers) or the X-Promo-Session header.
     */
    @PostMapping(value = "/events", consumes = {"text/plain", "application/json", "*/*"})
    public Map<String, Integer> events(@RequestBody String raw,
                                       @RequestHeader(value = "X-Promo-Session", required = false) String headerSession) {
        if (raw == null || raw.length() > MAX_BEACON_BYTES) {
            throw new BadRequestException("Beacon payload too large.");
        }
        record Body(String session, List<PromoEventService.BeaconEvent> events) {
        }
        Body body;
        try {
            body = objectMapper.readValue(raw, Body.class);
        } catch (Exception e) {
            throw new BadRequestException("Malformed beacon payload.");
        }
        String session = body.session() != null ? body.session() : headerSession;
        int recorded = eventService.ingestBeacon(new PromoEventService.Beacon(body.events()), session);
        return Map.of("recorded", recorded);
    }
}
