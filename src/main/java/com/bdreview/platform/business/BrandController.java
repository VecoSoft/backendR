package com.bdreview.platform.business;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/brands")
public class BrandController {

    private final BusinessService businessService;

    public BrandController(BusinessService businessService) {
        this.businessService = businessService;
    }

    @GetMapping("/{slug}/branches")
    public ResponseEntity<List<BusinessResponse>> branches(@PathVariable String slug) {
        return ResponseEntity.ok(businessService.businessesForBrand(slug));
    }
}
