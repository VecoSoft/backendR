package com.bdreview.platform.search;

import com.bdreview.platform.business.PriceTier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Public smart-search endpoints. Both sit under /api/v1/businesses/** so they inherit the existing
 * public-GET security rule; the plain /businesses/search filter endpoint (carousels, category/area
 * browsing) is untouched.
 */
@RestController
@RequestMapping("/api/v1/businesses")
public class SmartSearchController {

    private final SmartSearchService smartSearchService;
    private final SearchRateLimiter rateLimiter;

    public SmartSearchController(SmartSearchService smartSearchService, SearchRateLimiter rateLimiter) {
        this.smartSearchService = smartSearchService;
        this.rateLimiter = rateLimiter;
    }

    private com.bdreview.platform.analytics.SearchLogService searchLog;

    @org.springframework.beans.factory.annotation.Autowired
    void setSearchLog(com.bdreview.platform.analytics.SearchLogService searchLog) {
        this.searchLog = searchLog;
    }

    @GetMapping("/smart-search")
    public ResponseEntity<SmartSearchResponse> smartSearch(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String priceTier,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "en") String lang,
            @RequestHeader(value = "X-Promo-Session", required = false) String promoSession,
            HttpServletRequest http) {
        rateLimiter.checkSearch(http.getRemoteAddr());
        boolean validCoords = lat != null && lng != null && Double.isFinite(lat) && Double.isFinite(lng)
                && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
        Double rating = minRating != null && minRating >= 0 && minRating <= 5 ? minRating : null;
        var request = new SmartSearchService.Request(q, categoryId, areaId, validPriceTier(priceTier), rating,
                validCoords ? lat : null, validCoords ? lng : null, sort,
                Math.min(Math.max(page, 0), 500), size, lang, promoSession);
        SmartSearchResponse response = smartSearchService.search(request);
        if (page == 0 && searchLog != null) {
            // V67 analytics: top and zero-result queries (async, never affects the search)
            searchLog.log(q, areaId, response.intent() == null ? null : response.intent().location(), categoryId,
                    response.results() == null ? 0 : response.results().totalElements(), "SMART",
                    com.bdreview.platform.common.CurrentUser.idOrNull());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/search-suggestions")
    public ResponseEntity<List<SearchSuggestion>> suggestions(@RequestParam(required = false) String q,
                                                              @RequestParam(defaultValue = "en") String lang,
                                                              HttpServletRequest http) {
        rateLimiter.checkSuggest(http.getRemoteAddr());
        // Same input → same answer for everyone, so let the browser reuse it while the user
        // backspaces and retypes.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(smartSearchService.suggest(q, lang));
    }

    private static String validPriceTier(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PriceTier.valueOf(raw).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
