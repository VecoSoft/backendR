package com.bdreview.platform.homepage;

import com.bdreview.platform.adminconfig.AdminConfigService;
import com.bdreview.platform.adminconfig.HomepageConfig;
import com.bdreview.platform.business.Category;
import com.bdreview.platform.business.CategoryRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.gallery.ObjectStorageClient;
import com.bdreview.platform.photomod.PhotoModerationService;
import com.bdreview.platform.photomod.PhotoSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * System → Homepage (V65). Public read model for the app's homepage (hero texts/image, featured
 * categories); Trending / Most loved curation is applied inside BusinessService's search for those
 * sorts. A new hero image goes through photo moderation like any other photo.
 */
@Service
public class HomepageService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    public record FeaturedCategory(UUID id, String name, String kind) {
    }

    public record PublicHomepage(String heroTitle, String heroSubtitle, String heroImageUrl,
                                 List<FeaturedCategory> featuredCategories) {
    }

    private final AdminConfigService adminConfig;
    private final CategoryRepository categoryRepository;
    private final PhotoModerationService photoModeration;
    private final ObjectStorageClient storage;

    public HomepageService(AdminConfigService adminConfig, CategoryRepository categoryRepository,
                           PhotoModerationService photoModeration, ObjectStorageClient storage) {
        this.adminConfig = adminConfig;
        this.categoryRepository = categoryRepository;
        this.photoModeration = photoModeration;
        this.storage = storage;
    }

    public PublicHomepage publicHomepage() {
        HomepageConfig c = adminConfig.homepage();
        return new PublicHomepage(blankToNull(c.getHeroTitle()), blankToNull(c.getHeroSubtitle()),
                blankToNull(c.getHeroImageUrl()), featured(c));
    }

    public List<FeaturedCategory> featured(HomepageConfig c) {
        Map<UUID, Category> byId = categoryRepository.findAllById(c.getFeaturedCategoryIds()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        return c.getFeaturedCategoryIds().stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(cat -> new FeaturedCategory(cat.getId(), cat.getName(),
                        cat.getKind() == null ? null : cat.getKind().name()))
                .toList();
    }

    /**
     * Stores an uploaded hero image and submits it for moderation. Returns true when it went live
     * immediately (photo approval off), false when it waits in Moderation → Photos.
     */
    @Transactional
    public boolean uploadHero(String filename, byte[] content, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        if (content == null || content.length == 0) {
            throw new BadRequestException("Choose an image.");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            throw new BadRequestException("The image can be at most 5 MB.");
        }
        String name = filename == null ? "hero.jpg" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!IMAGE_EXTENSIONS.contains(ext)) {
            throw new BadRequestException("Upload a JPG, PNG or WebP image.");
        }
        String key = "homepage/hero/" + UUID.randomUUID() + "-" + name;
        String url = storage.putObject(key, content);
        HomepageConfig before = adminConfig.homepage();
        String live = photoModeration.admitField(PhotoSource.HERO, PhotoSource.HERO_SOURCE_ID, null, CurrentUser.idOrNull(),
                before.getHeroImageUrl(), url);
        if (url.equals(live)) {
            HomepageConfig after = adminConfig.homepage();
            after.setHeroImageUrl(url);
            adminConfig.saveHomepage(after, reason);
            return true;
        }
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
