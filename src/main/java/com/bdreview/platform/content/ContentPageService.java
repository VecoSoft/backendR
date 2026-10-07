package com.bdreview.platform.content;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Content pages (V67, System → Content): Terms, Privacy, FAQ and Help as Markdown in English and
 * Bangla. Every save bumps the version and keeps the previous text in content_page_version, so
 * any version can be viewed and restored. The public page shows "last updated". Until a page is
 * written in the panel, a short built-in text is served (version 0).
 */
@Service
public class ContentPageService {

    public static final List<String> SLUGS = List.of("terms", "privacy", "faq", "help");
    public static final List<String> LOCALES = List.of("en", "bn");

    public record Page(String slug, String locale, String title, String bodyMd, int version, Instant updatedAt, boolean builtIn) {
    }

    private static final Map<String, String[]> DEFAULTS = Map.of(
            "terms:en", new String[]{"Terms of use", "By using this app you agree to post honest reviews, respect other people and follow the law of Bangladesh. We may remove content or restrict accounts that break these rules."},
            "terms:bn", new String[]{"ব্যবহারের শর্তাবলী", "এই অ্যাপ ব্যবহার করে আপনি সৎ রিভিউ দেওয়া, অন্যদের সম্মান করা এবং বাংলাদেশের আইন মেনে চলতে সম্মত হচ্ছেন। নিয়ম ভাঙলে আমরা কনটেন্ট সরাতে বা অ্যাকাউন্ট সীমিত করতে পারি।"},
            "privacy:en", new String[]{"Privacy policy", "We keep your phone number private and use it only to sign you in and send the notifications you ask for. We never sell your data."},
            "privacy:bn", new String[]{"গোপনীয়তা নীতি", "আপনার ফোন নম্বর গোপন রাখা হয় এবং শুধু সাইন ইন ও আপনার চাওয়া নোটিফিকেশন পাঠাতে ব্যবহার করা হয়। আমরা কখনো আপনার তথ্য বিক্রি করি না।"},
            "faq:en", new String[]{"Frequently asked questions", "## How do I write a review?\nOpen a business page and tap **Write a review**.\n\n## How do I claim my business?\nOpen the listing and tap **Claim this business**."},
            "faq:bn", new String[]{"সাধারণ প্রশ্নোত্তর", "## কিভাবে রিভিউ লিখব?\nব্যবসার পেজ খুলে **রিভিউ লিখুন** চাপুন।\n\n## কিভাবে আমার ব্যবসা দাবি করব?\nলিস্টিং খুলে **এই ব্যবসা দাবি করুন** চাপুন।"},
            "help:en", new String[]{"Help", "Can't find what you need? Use **Contact support** below and we'll reply in the app."},
            "help:bn", new String[]{"সাহায্য", "যা খুঁজছেন পাচ্ছেন না? নিচের **সাপোর্টে যোগাযোগ** ব্যবহার করুন, আমরা অ্যাপেই উত্তর দেব।"});

    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    public ContentPageService(JdbcTemplate jdbc, AuditLogService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Page get(String slug, String locale) {
        requireSlug(slug);
        String loc = LOCALES.contains(locale) ? locale : "en";
        List<Page> rows = jdbc.query("SELECT * FROM content_page WHERE slug = ? AND locale = ?", (rs, i) -> new Page(
                rs.getString("slug"), rs.getString("locale"), rs.getString("title"), rs.getString("body_md"),
                rs.getInt("version"), rs.getTimestamp("updated_at").toInstant(), false), slug, loc);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        String[] d = DEFAULTS.get(slug + ":" + loc);
        return new Page(slug, loc, d[0], d[1], 0, null, true);
    }

    /** Public read: the requested language, falling back to English when that language was never written. */
    public Page publicPage(String slug, String locale) {
        Page p = get(slug, locale);
        if (p.builtIn() && "bn".equals(p.locale())) {
            Page en = get(slug, "en");
            if (!en.builtIn()) {
                return en;
            }
        }
        return p;
    }

    public List<Map<String, Object>> overview() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String slug : SLUGS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", slug);
            for (String loc : LOCALES) {
                row.put(loc, get(slug, loc));
            }
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> history(String slug, String locale) {
        requireSlug(slug);
        return jdbc.queryForList("""
                SELECT v.id, v.version, v.title, v.reason, v.created_at, u.name AS updated_by_name
                FROM content_page_version v LEFT JOIN app_user u ON u.id = v.updated_by
                WHERE v.slug = ? AND v.locale = ? ORDER BY v.version DESC
                """, slug, locale);
    }

    public Map<String, Object> version(UUID versionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM content_page_version WHERE id = ?", versionId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Version not found");
        }
        return rows.get(0);
    }

    @Transactional
    public Page save(String slug, String locale, String title, String bodyMd, String reason) {
        requireSlug(slug);
        if (!LOCALES.contains(locale)) {
            throw new BadRequestException("Unknown language.");
        }
        String t = title == null ? "" : title.trim();
        String b = bodyMd == null ? "" : bodyMd.strip();
        if (t.isEmpty() || t.length() > 160) {
            throw new BadRequestException("The title is required (up to 160 characters).");
        }
        if (b.isEmpty() || b.length() > 100_000) {
            throw new BadRequestException("The page text is required (up to 100,000 characters).");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        Page before = get(slug, locale);
        if (!before.builtIn() && before.title().equals(t) && before.bodyMd().equals(b)) {
            throw new BadRequestException("Nothing changed.");
        }
        int version = before.version() + 1;
        UUID actor = CurrentUser.idOrNull();
        jdbc.update("""
                INSERT INTO content_page (slug, locale, title, body_md, version, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (slug, locale) DO UPDATE SET title = EXCLUDED.title, body_md = EXCLUDED.body_md,
                    version = EXCLUDED.version, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, slug, locale, t, b, version, actor);
        jdbc.update("""
                INSERT INTO content_page_version (id, slug, locale, version, title, body_md, reason, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), slug, locale, version, t, b, reason.trim(), actor);
        audit.record("CONTENT_PAGE", UUID.nameUUIDFromBytes((slug + ":" + locale).getBytes()), "CONTENT_PAGE_UPDATED", reason.trim(),
                Map.of("page", slug + "/" + locale, "version", before.version()), Map.of("page", slug + "/" + locale, "version", version));
        return get(slug, locale);
    }

    @Transactional
    public Page restore(UUID versionId, String reason) {
        Map<String, Object> v = version(versionId);
        return save((String) v.get("slug"), (String) v.get("locale"), (String) v.get("title"), (String) v.get("body_md"),
                reason == null || reason.isBlank() ? null : reason.trim() + " (restored v" + v.get("version") + ")");
    }

    private static void requireSlug(String slug) {
        if (!SLUGS.contains(slug)) {
            throw new ResourceNotFoundException("Page not found");
        }
    }

    @SuppressWarnings("unused")
    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
