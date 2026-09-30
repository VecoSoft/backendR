package com.bdreview.platform.promo;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.community.settings.CommunitySettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side content rules for anything a business publishes (V58): minimum length, banned
 * promotion categories (keyword lists below, Bangla + English, plus admin extras), no
 * superlative/false claims in business copy, and links only to the business's own pages.
 */
@Component
public class PromotionContentRules {

    /** Keyword lists per banned category code. Matched as whole words, case-insensitive. */
    static final Map<String, List<String>> CATEGORY_KEYWORDS = Map.of(
            "MEDICAL_CLAIMS", List.of("cure", "cures", "guaranteed cure", "100% cure", "cancer cure", "miracle", "miracle cure",
                    "guaranteed weight loss", "lose weight fast", "নিরাময়", "রোগ মুক্তি", "গ্যারান্টি সহ নিরাময়", "অলৌকিক"),
            "ALCOHOL", List.of("alcohol", "beer", "wine", "whisky", "whiskey", "vodka", "rum", "liquor", "মদ", "বিয়ার", "মদ্যপান"),
            "TOBACCO", List.of("tobacco", "cigarette", "cigarettes", "cigar", "vape", "vaping", "hookah", "shisha", "সিগারেট", "তামাক", "হুক্কা"),
            "WEAPONS", List.of("gun", "guns", "pistol", "rifle", "firearm", "ammunition", "weapon", "weapons", "অস্ত্র", "পিস্তল", "বন্দুক"),
            "POLITICAL", List.of("vote for", "election", "elections", "political party", "campaign rally", "ভোট দিন", "নির্বাচন", "রাজনৈতিক দল"));

    public static final Map<String, String> CATEGORY_LABELS = Map.of(
            "MEDICAL_CLAIMS", "Medical claims",
            "ALCOHOL", "Alcohol",
            "TOBACCO", "Tobacco",
            "WEAPONS", "Weapons",
            "POLITICAL", "Political");

    private static final Pattern URL = Pattern.compile("(https?://[^\\s)\\]]+|www\\.[^\\s)\\]]+)", Pattern.CASE_INSENSITIVE);

    private final String frontendHost;

    public PromotionContentRules(@Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.frontendHost = hostOf(frontendUrl);
    }

    public void check(CommunitySettings.Promotions settings, Business business, String... texts) {
        String joined = String.join("\n", Arrays.stream(texts).filter(Objects::nonNull).toList());
        String normalized = normalize(joined);
        for (String code : settings.getBannedCategories()) {
            for (String keyword : CATEGORY_KEYWORDS.getOrDefault(code, List.of())) {
                if (containsWord(normalized, normalize(keyword))) {
                    throw new BadRequestException("Promotions about " + CATEGORY_LABELS.getOrDefault(code, code).toLowerCase(Locale.ROOT)
                            + " aren't allowed on Jachai (found \"" + keyword + "\").");
                }
            }
        }
        for (String keyword : settings.getExtraBannedKeywords()) {
            if (containsWord(normalized, normalize(keyword))) {
                throw new BadRequestException("This promotion contains a word that isn't allowed: \"" + keyword + "\".");
            }
        }
        checkLinks(joined, business);
    }

    /** Body length rule, applied to the post body (the creative's own text is separately capped). */
    public void checkBody(CommunitySettings.Promotions settings, String body) {
        int length = body == null ? 0 : body.strip().codePointCount(0, body.strip().length());
        if (length < settings.getMinBodyLength()) {
            throw new BadRequestException("Write at least " + settings.getMinBodyLength() + " characters.");
        }
    }

    private void checkLinks(String text, Business business) {
        Set<String> ownHosts = new HashSet<>();
        for (String url : new String[]{business.getWebsiteUrl(), business.getFacebookUrl(), business.getInstagramUrl()}) {
            String host = hostOf(url);
            if (host != null) {
                ownHosts.add(host);
            }
        }
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            String url = raw.toLowerCase(Locale.ROOT).startsWith("www.") ? "https://" + raw : raw;
            String host = hostOf(url);
            String path = pathOf(url);
            boolean ownJachaiPage = host != null && host.equals(frontendHost)
                    && (path.startsWith("/business/" + business.getSlug()) || path.startsWith("/p/"));
            boolean ownSite = host != null && ownHosts.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
            // Facebook/Instagram hosts are shared by everyone — only the business's own profile path counts.
            if (ownSite && (host.endsWith("facebook.com") || host.endsWith("instagram.com"))) {
                String ownPath = pathOf(host.endsWith("facebook.com") ? business.getFacebookUrl() : business.getInstagramUrl());
                ownSite = !ownPath.isBlank() && !"/".equals(ownPath) && path.startsWith(ownPath);
            }
            if (!ownJachaiPage && !ownSite) {
                throw new BadRequestException("Links in a business post may only point to your own business pages.");
            }
        }
    }

    private static boolean containsWord(String haystack, String needle) {
        if (needle.isBlank()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.codePointBefore(at));
            int end = at + needle.length();
            boolean endOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.codePointAt(end));
            // Bangla words glue suffixes on (মদের) — accept a Bengali-script continuation as a match.
            if (startOk && (endOk || isBengali(haystack.codePointAt(end)))) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isBengali(int cp) {
        return cp >= 0x0980 && cp <= 0x09FF;
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String u = url.contains("://") ? url : "https://" + url;
            String host = URI.create(u.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pathOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String u = url.contains("://") ? url : "https://" + url;
            String p = URI.create(u.trim()).getPath();
            return p == null ? "" : p.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
