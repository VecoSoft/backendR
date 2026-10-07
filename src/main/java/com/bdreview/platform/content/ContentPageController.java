package com.bdreview.platform.content;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/** Public content pages (V67): GET /api/v1/content/{terms|privacy|faq|help}?lang=en|bn. */
@RestController
@RequestMapping("/api/v1/content")
public class ContentPageController {

    private final ContentPageService pages;

    public ContentPageController(ContentPageService pages) {
        this.pages = pages;
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ContentPageService.Page> page(@PathVariable String slug, @RequestParam(defaultValue = "en") String lang) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(pages.publicPage(slug, lang));
    }
}
