package com.bdreview.platform.notification;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.SharedCache;
import com.bdreview.platform.moderation.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Editable notification texts (V67, System → Notifications → Templates) in English and Bangla with
 * {@code {variable}} placeholders. A row in {@code notification_template} overrides the built-in
 * default; the recipient's preferred language picks the locale. Cached briefly, evicted on save.
 */
@Service
public class NotificationTemplateService {

    private static final Logger log = LoggerFactory.getLogger(NotificationTemplateService.class);
    private static final Pattern VAR = Pattern.compile("\\{([a-zA-Z]+)}");
    private static final java.time.Duration TTL = java.time.Duration.ofSeconds(60);
    private static final String CACHE_KEY = "notification_templates";
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Text>> ROWS_TYPE = new com.fasterxml.jackson.core.type.TypeReference<>() {
    };

    /** Every editable message, its variables and its built-in texts. */
    public enum Key {
        ORDER_STATUS("Order status changed", List.of("orderNumber", "status", "businessName"),
                "Order {orderNumber} {status}", "Your order {orderNumber} from {businessName} is now {status}.",
                "অর্ডার {orderNumber} {status}", "{businessName}-এ আপনার অর্ডার {orderNumber} এখন {status}।"),
        BOOKING_STATUS("Booking status changed", List.of("bookingNumber", "status", "businessName"),
                "Booking {bookingNumber} {status}", "Your booking {bookingNumber} at {businessName} is now {status}.",
                "বুকিং {bookingNumber} {status}", "{businessName}-এ আপনার বুকিং {bookingNumber} এখন {status}।"),
        REPORT_ACTION_TAKEN("Report outcome — action taken", List.of("referenceCode"),
                "Report resolved", "Your report (Ref: {referenceCode}) has been reviewed — action was taken.",
                "রিপোর্ট নিষ্পত্তি হয়েছে", "আপনার রিপোর্ট (Ref: {referenceCode}) পর্যালোচনা করা হয়েছে — ব্যবস্থা নেওয়া হয়েছে।"),
        REPORT_DISMISSED("Report outcome — dismissed", List.of("referenceCode"),
                "Report resolved", "Your report (Ref: {referenceCode}) has been reviewed — insufficient evidence was found.",
                "রিপোর্ট নিষ্পত্তি হয়েছে", "আপনার রিপোর্ট (Ref: {referenceCode}) পর্যালোচনা করা হয়েছে — যথেষ্ট প্রমাণ পাওয়া যায়নি।"),
        RESTRICTION_NOTICE("Community restriction notice", List.of("restriction", "reason", "until"),
                "Your community access is restricted", "{restriction} until {until}. Reason: {reason}",
                "কমিউনিটিতে আপনার অ্যাক্সেস সীমিত", "{restriction} — {until} পর্যন্ত। কারণ: {reason}"),
        VERIFICATION_APPROVED("Verification approved", List.of("businessName"),
                "{businessName} is now verified", "Your verification request was approved — the Verified badge now shows on your listing.",
                "{businessName} এখন ভেরিফায়েড", "আপনার ভেরিফিকেশন অনুরোধ অনুমোদিত হয়েছে — আপনার লিস্টিংয়ে এখন ভেরিফায়েড ব্যাজ দেখাচ্ছে।"),
        VERIFICATION_REJECTED("Verification rejected", List.of("businessName", "reason"),
                "Verification request not approved", "Your verification request for {businessName} was not approved: {reason}",
                "ভেরিফিকেশন অনুরোধ অনুমোদিত হয়নি", "{businessName}-এর ভেরিফিকেশন অনুরোধ অনুমোদিত হয়নি: {reason}"),
        PHOTO_REJECTED("Photo rejected", List.of("photoType", "reason"),
                "Your photo wasn't approved", "Your {photoType} wasn't approved by our moderators: {reason}",
                "আপনার ছবি অনুমোদিত হয়নি", "আপনার {photoType} আমাদের মডারেটররা অনুমোদন করেননি: {reason}"),
        // V70 sign-in emails: the title is the e-mail subject, the body its text.
        EMAIL_VERIFY_CODE("Email: verification code", List.of("name", "code", "minutes"),
                "{code} is your Jachai verification code",
                "Hi {name},\n\nYour Jachai verification code is {code}. It expires in {minutes} minutes.\n\n"
                        + "If you didn't ask for this, you can ignore this email.",
                "{code} আপনার জাচাই ভেরিফিকেশন কোড",
                "হ্যালো {name},\n\nআপনার জাচাই ভেরিফিকেশন কোড {code}। কোডটি {minutes} মিনিটের মধ্যে ব্যবহার করুন।\n\n"
                        + "আপনি অনুরোধ না করে থাকলে এই ইমেইলটি উপেক্ষা করুন।"),
        EMAIL_RESET_CODE("Email: password reset code", List.of("name", "code", "minutes"),
                "{code} is your Jachai password reset code",
                "Hi {name},\n\nUse {code} to reset your Jachai password. It expires in {minutes} minutes.\n\n"
                        + "If you didn't ask to reset your password, ignore this email — your password stays the same.",
                "{code} আপনার জাচাই পাসওয়ার্ড রিসেট কোড",
                "হ্যালো {name},\n\nজাচাই পাসওয়ার্ড রিসেট করতে {code} কোডটি ব্যবহার করুন। কোডটি {minutes} মিনিটের মধ্যে ব্যবহার করুন।\n\n"
                        + "আপনি পাসওয়ার্ড রিসেটের অনুরোধ না করে থাকলে এই ইমেইলটি উপেক্ষা করুন — আপনার পাসওয়ার্ড বদলাবে না।"),
        EMAIL_PASSWORD_CHANGED("Email: password changed", List.of("name", "email", "time"),
                "Your Jachai password was changed",
                "Hi {name},\n\nThe password for your Jachai account ({email}) was changed on {time}, and every device was signed out.\n\n"
                        + "If this wasn't you, reset your password right away with \"Forgot password\" and contact Jachai support.",
                "আপনার জাচাই পাসওয়ার্ড পরিবর্তন করা হয়েছে",
                "হ্যালো {name},\n\n{time}-এ আপনার জাচাই অ্যাকাউন্টের ({email}) পাসওয়ার্ড পরিবর্তন করা হয়েছে এবং সব ডিভাইস থেকে সাইন আউট করা হয়েছে।\n\n"
                        + "এটি আপনি না করে থাকলে এখনই \"পাসওয়ার্ড ভুলে গেছেন\" দিয়ে পাসওয়ার্ড রিসেট করুন এবং জাচাই সাপোর্টে যোগাযোগ করুন।");

        private final String label;
        private final List<String> variables;
        private final String enTitle;
        private final String enBody;
        private final String bnTitle;
        private final String bnBody;

        Key(String label, List<String> variables, String enTitle, String enBody, String bnTitle, String bnBody) {
            this.label = label;
            this.variables = variables;
            this.enTitle = enTitle;
            this.enBody = enBody;
            this.bnTitle = bnTitle;
            this.bnBody = bnBody;
        }

        public String label() {
            return label;
        }

        public List<String> variables() {
            return variables;
        }

        public Text defaults(String locale) {
            return "bn".equals(locale) ? new Text(bnTitle, bnBody) : new Text(enTitle, enBody);
        }
    }

    public record Text(String title, String body) {
    }

    /** One template as the admin page shows it. */
    public record View(Key key, Text en, Text bn, boolean enCustom, boolean bnCustom) {
    }

    private final JdbcTemplate jdbc;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final SharedCache cache;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public NotificationTemplateService(JdbcTemplate jdbc, NotificationService notificationService,
                                       UserRepository userRepository, AuditLogService auditLogService,
                                       SharedCache cache, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    // ---------------------------------------------------------------- reading

    public Text text(Key key, String locale) {
        String loc = "bn".equals(locale) ? "bn" : "en";
        Text custom = rows().get(key.name() + "|" + loc);
        return custom != null ? custom : key.defaults(loc);
    }

    public Text render(Key key, String locale, Map<String, ?> vars) {
        Text t = text(key, locale);
        return new Text(fill(t.title(), vars), fill(t.body(), vars));
    }

    public List<View> all() {
        Map<String, Text> rows = rows();
        return Arrays.stream(Key.values()).map(k -> new View(k, text(k, "en"), text(k, "bn"),
                rows.containsKey(k.name() + "|en"), rows.containsKey(k.name() + "|bn"))).toList();
    }

    /** Sample values for previews: each variable shown as an example. */
    public static Map<String, String> sampleVars(Key key) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String v : key.variables()) {
            m.put(v, switch (v) {
                case "orderNumber" -> "ORD-1042";
                case "bookingNumber" -> "BK-2210";
                case "status" -> "accepted";
                case "businessName" -> "Kacchi Bhai Dhanmondi";
                case "referenceCode" -> "RP-8F3K2";
                case "reason" -> "Doesn't show the business";
                case "restriction" -> "You've been muted in the community";
                case "until" -> "12 Oct 2026, 6:00 PM";
                case "photoType" -> "cover photo";
                case "name" -> "Nusrat";
                case "code" -> "482913";
                case "minutes" -> "10";
                case "email" -> "nusrat@example.com";
                case "time" -> "7 Oct 2026, 9:15 PM";
                default -> v;
            });
        }
        return m;
    }

    // ---------------------------------------------------------------- sending

    /** Renders the template in the recipient's language and creates the in-app notification (never throws). */
    public void notify(UUID recipientUserId, Key key, Map<String, ?> vars, NotificationType type,
                       String entityType, UUID entityId) {
        if (recipientUserId == null) {
            return;
        }
        try {
            String locale = userRepository.findById(recipientUserId).map(User::getPreferredLanguage).orElse("en");
            Text t = render(key, locale, vars);
            notificationService.create(recipientUserId, type, t.title(), t.body(), entityType, entityId, NotificationChannel.IN_APP);
        } catch (RuntimeException e) {
            log.warn("Could not send {} notification to {}: {}", key, recipientUserId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- editing

    @Transactional
    public void save(Key key, String locale, String title, String body, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        String loc = "bn".equals(locale) ? "bn" : "en";
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            throw new BadRequestException("Title and text can't be empty.");
        }
        Set<String> unknown = new TreeSet<>();
        for (String s : List.of(title, body)) {
            Matcher m = VAR.matcher(s);
            while (m.find()) {
                if (!key.variables().contains(m.group(1))) {
                    unknown.add("{" + m.group(1) + "}");
                }
            }
        }
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Unknown variable(s) " + String.join(", ", unknown) + " — available: "
                    + key.variables().stream().map(v -> "{" + v + "}").reduce((a, b) -> a + ", " + b).orElse("none"));
        }
        Text before = text(key, loc);
        jdbc.update("""
                INSERT INTO notification_template (template_key, locale, title, body, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (template_key, locale) DO UPDATE SET title = EXCLUDED.title, body = EXCLUDED.body,
                    updated_by = EXCLUDED.updated_by, updated_at = now()
                """, key.name(), loc, title.trim(), body.trim(), CurrentUser.idOrNull());
        cache.evictAfterCommit(CACHE_KEY);
        auditLogService.record("NOTIFICATION_TEMPLATE", null, "TEMPLATE_" + key.name() + "_" + loc.toUpperCase(), reason.trim(),
                Map.of("title", before.title(), "body", before.body()), Map.of("title", title.trim(), "body", body.trim()));
    }

    @Transactional
    public void reset(Key key, String locale, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
        String loc = "bn".equals(locale) ? "bn" : "en";
        Text before = text(key, loc);
        jdbc.update("DELETE FROM notification_template WHERE template_key = ? AND locale = ?", key.name(), loc);
        cache.evictAfterCommit(CACHE_KEY);
        Text after = key.defaults(loc);
        auditLogService.record("NOTIFICATION_TEMPLATE", null, "TEMPLATE_" + key.name() + "_" + loc.toUpperCase() + "_RESET",
                reason.trim(), Map.of("title", before.title(), "body", before.body()), Map.of("title", after.title(), "body", after.body()));
    }

    // ----------------------------------------------------------------

    /** Admin-customised templates, cached in Redis (shared by every instance) and evicted on save. */
    private Map<String, Text> rows() {
        String json = cache.get(CACHE_KEY, TTL, () -> {
            Map<String, Text> rows = new HashMap<>();
            jdbc.query("SELECT template_key, locale, title, body FROM notification_template", rs -> {
                rows.put(rs.getString("template_key") + "|" + rs.getString("locale"), new Text(rs.getString("title"), rs.getString("body")));
            });
            try {
                return objectMapper.writeValueAsString(rows);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            return objectMapper.readValue(json, ROWS_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable notification template cache", e);
        }
    }

    static String fill(String text, Map<String, ?> vars) {
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
