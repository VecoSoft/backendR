package com.bdreview.platform.admin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * System → Analytics (V67): platform totals and daily series for a 7/30/90-day window, search
 * insights (top queries, zero-result queries by area), report reasons over time with average
 * resolution time, and the photo queue's average wait. Plain SQL over the existing tables; every
 * table can be exported as CSV. Days are Bangladesh calendar days.
 */
@Service
public class AdminAnalyticsService {

    public static final String TZ = "Asia/Dhaka";
    public static final List<Integer> RANGES = List.of(7, 30, 90);

    /** Daily series columns, in display order, with their labels. */
    public static final LinkedHashMap<String, String> SERIES = new LinkedHashMap<>();

    static {
        SERIES.put("signups_consumer", "Signups (users)");
        SERIES.put("signups_business", "Signups (business)");
        SERIES.put("dau", "Daily active users");
        SERIES.put("reviews", "Reviews");
        SERIES.put("community_posts", "Community posts");
        SERIES.put("orders", "Orders");
        SERIES.put("order_value", "Order value (৳)");
        SERIES.put("bookings", "Bookings");
        SERIES.put("offer_redemptions", "Offer redemptions");
        SERIES.put("boost_revenue", "Boost revenue (৳)");
    }

    private final JdbcTemplate jdbc;

    public AdminAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static int clampDays(Integer days) {
        return days != null && RANGES.contains(days) ? days : 30;
    }

    /** One row per day (oldest first): day + every SERIES column. */
    public List<Map<String, Object>> daily(int days) {
        String d = "(%s AT TIME ZONE '" + TZ + "')::date";
        String sql = """
                WITH days AS (
                    SELECT generate_series((now() AT TIME ZONE '%1$s')::date - (? - 1), (now() AT TIME ZONE '%1$s')::date, interval '1 day')::date AS day
                ),
                active AS (
                    SELECT user_id, %2$s AS day FROM user_login_event WHERE outcome = 'SUCCESS' AND created_at > now() - make_interval(days => ?)
                    UNION SELECT user_id, %3$s FROM review WHERE created_at > now() - make_interval(days => ?)
                    UNION SELECT author_user_id, %3$s FROM community_post WHERE created_at > now() - make_interval(days => ?)
                    UNION SELECT author_user_id, %3$s FROM community_post_comment WHERE created_at > now() - make_interval(days => ?)
                    UNION SELECT customer_user_id, %3$s FROM business_order WHERE created_at > now() - make_interval(days => ?)
                    UNION SELECT customer_user_id, %3$s FROM business_booking WHERE created_at > now() - make_interval(days => ?)
                    UNION SELECT sender_user_id, %3$s FROM message WHERE created_at > now() - make_interval(days => ?)
                )
                SELECT to_char(d.day, 'YYYY-MM-DD') AS day,
                    (SELECT count(*) FROM app_user u WHERE u.role = 'CONSUMER' AND %4$s = d.day) AS signups_consumer,
                    (SELECT count(*) FROM app_user u WHERE u.role = 'BUSINESS_OWNER' AND %4$s = d.day) AS signups_business,
                    (SELECT count(DISTINCT a.user_id) FROM active a WHERE a.day = d.day) AS dau,
                    (SELECT count(*) FROM review r WHERE %5$s = d.day) AS reviews,
                    (SELECT count(*) FROM community_post p WHERE %6$s = d.day) AS community_posts,
                    (SELECT count(*) FROM business_order o WHERE %7$s = d.day) AS orders,
                    (SELECT coalesce(sum(o.total_amount), 0) FROM business_order o WHERE %7$s = d.day
                        AND o.status NOT IN ('CANCELLED', 'REJECTED')) AS order_value,
                    (SELECT count(*) FROM business_booking k WHERE %8$s = d.day) AS bookings,
                    (SELECT count(*) FROM offer_claim c WHERE c.redeemed_at IS NOT NULL AND %9$s = d.day) AS offer_redemptions,
                    (SELECT coalesce(sum(b.paid_amount), 0) FROM boost b WHERE b.payment_verified_at IS NOT NULL AND %10$s = d.day
                        AND b.status <> 'REFUNDED') AS boost_revenue
                FROM days d
                ORDER BY d.day
                """.formatted(TZ, d.formatted("created_at"), d.formatted("created_at"), d.formatted("u.created_at"),
                d.formatted("r.created_at"), d.formatted("p.created_at"), d.formatted("o.created_at"),
                d.formatted("k.created_at"), d.formatted("c.redeemed_at"), d.formatted("b.payment_verified_at"));
        return jdbc.queryForList(sql, days, days, days, days, days, days, days, days);
    }

    /** Sum of each series over the window (DAU → average per day). */
    public Map<String, Object> totals(List<Map<String, Object>> daily) {
        Map<String, Object> totals = new LinkedHashMap<>();
        for (String key : SERIES.keySet()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> row : daily) {
                Object v = row.get(key);
                sum = sum.add(v instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(v == null ? 0 : v)));
            }
            totals.put(key, "dau".equals(key) && !daily.isEmpty()
                    ? sum.divide(BigDecimal.valueOf(daily.size()), 0, java.math.RoundingMode.HALF_UP) : sum);
        }
        return totals;
    }

    public List<Map<String, Object>> topQueries(int days, int limit) {
        return jdbc.queryForList("""
                SELECT normalized AS query, count(*) AS searches, round(avg(result_count)) AS avg_results,
                       sum(CASE WHEN result_count = 0 THEN 1 ELSE 0 END) AS zero_result_searches
                FROM search_log WHERE created_at > now() - make_interval(days => ?)
                GROUP BY normalized ORDER BY searches DESC, query LIMIT ?
                """, days, limit);
    }

    /** Queries that found nothing, grouped by query and area — the listings worth adding. */
    public List<Map<String, Object>> zeroResultQueries(int days, int limit) {
        return jdbc.queryForList("""
                SELECT s.normalized AS query, coalesce(a.name, s.area_label, '(any area)') AS area, count(*) AS searches,
                       max(s.created_at) AS last_searched
                FROM search_log s LEFT JOIN area a ON a.id = s.area_id
                WHERE s.result_count = 0 AND s.created_at > now() - make_interval(days => ?)
                GROUP BY s.normalized, coalesce(a.name, s.area_label, '(any area)')
                ORDER BY searches DESC, last_searched DESC LIMIT ?
                """, days, limit);
    }

    /** Reports filed per day by reason (one row per day, a column per reason). */
    public List<Map<String, Object>> reportsByReason(int days) {
        return jdbc.queryForList("""
                SELECT to_char((r.created_at AT TIME ZONE '%s')::date, 'YYYY-MM-DD') AS day,
                       count(*) FILTER (WHERE r.reason = 'SPAM') AS spam,
                       count(*) FILTER (WHERE r.reason = 'FAKE') AS fake,
                       count(*) FILTER (WHERE r.reason = 'OFFENSIVE') AS offensive,
                       count(*) FILTER (WHERE r.reason NOT IN ('SPAM', 'FAKE', 'OFFENSIVE')) AS other,
                       count(*) AS total
                FROM report r WHERE r.created_at > now() - make_interval(days => ?)
                GROUP BY 1 ORDER BY 1
                """.formatted(TZ), days);
    }

    public Map<String, Object> moderationTimes(int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgReportResolveHours", jdbc.queryForObject("""
                SELECT round((extract(epoch FROM avg(resolved_at - created_at)) / 3600)::numeric, 1)
                FROM report WHERE resolved_at IS NOT NULL AND resolved_at > now() - make_interval(days => ?)
                """, BigDecimal.class, days));
        m.put("reportsResolved", jdbc.queryForObject(
                "SELECT count(*) FROM report WHERE resolved_at IS NOT NULL AND resolved_at > now() - make_interval(days => ?)", Long.class, days));
        m.put("reportsOpen", jdbc.queryForObject("SELECT count(*) FROM report WHERE status = 'PENDING'", Long.class));
        m.put("avgPhotoWaitHours", jdbc.queryForObject("""
                SELECT round((extract(epoch FROM avg(reviewed_at - created_at)) / 3600)::numeric, 1)
                FROM photo_moderation WHERE reviewed_at IS NOT NULL AND status IN ('APPROVED', 'REJECTED')
                  AND reviewed_at > now() - make_interval(days => ?)
                """, BigDecimal.class, days));
        m.put("photosPending", jdbc.queryForObject("SELECT count(*) FROM photo_moderation WHERE status = 'PENDING'", Long.class));
        m.put("oldestPendingPhotoHours", jdbc.queryForObject("""
                SELECT round((extract(epoch FROM now() - min(created_at)) / 3600)::numeric, 1) FROM photo_moderation WHERE status = 'PENDING'
                """, BigDecimal.class));
        return m;
    }

    /** CSV of any analytics table (header from the first row's keys, RFC 4180 quoting). */
    public String csv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        sb.append(String.join(",", cols)).append("\r\n");
        for (Map<String, Object> row : rows) {
            StringJoiner line = new StringJoiner(",");
            for (String c : cols) {
                Object v = row.get(c);
                String s = v == null ? "" : String.valueOf(v);
                if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
                    s = "'" + s; // keep spreadsheet formulas from executing
                }
                line.add(s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s);
            }
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static LocalDate today() {
        return LocalDate.now(ZoneId.of(TZ));
    }
}
