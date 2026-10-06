package com.bdreview.platform.homepage;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public homepage content curated in System → Homepage (V65). */
@RestController
@RequestMapping("/api/v1/home")
public class HomepageController {

    private final HomepageService homepageService;

    public HomepageController(HomepageService homepageService) {
        this.homepageService = homepageService;
    }

    @GetMapping
    public ResponseEntity<HomepageService.PublicHomepage> homepage() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(homepageService.publicHomepage());
    }
}
