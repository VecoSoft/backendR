package com.bdreview.platform.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Brand → Branches: write-side only (create-or-reuse a brand by name). Reading a brand's
 * branches lives on BusinessService instead, since that's where the batched gallery/claimed/
 * brand-summary response-building helpers already are (see BusinessService#businessesForBrand).
 */
@Service
public class BrandService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BrandRepository brandRepository;

    public BrandService(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    /**
     * Called from BusinessService#create when the owner types a new chain name. Looks up an
     * existing live brand with that exact name first, so two typo'd create calls don't spawn
     * duplicate brands for the same real chain — case-insensitive, since "KFC" and "kfc" are
     * obviously the same brand.
     */
    @Transactional
    public Brand createOrReuseByName(String name) {
        return brandRepository.findByNameIgnoreCaseAndDeletedAtIsNull(name.trim())
                .orElseGet(() -> brandRepository.save(Brand.builder()
                        .name(name.trim())
                        .slug(generateUniqueSlug(name.trim()))
                        .build()));
    }

    /** Same generate-then-check-collision pattern as BusinessService#generateUniqueSlug, scoped to brand instead. */
    private String generateUniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "brand";
        }
        String slug = base;
        while (brandRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            slug = base + "-" + Integer.toHexString(RANDOM.nextInt(0xFFFFFF));
        }
        return slug;
    }
}
