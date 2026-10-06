package com.bdreview.platform.admin.service;

import com.bdreview.platform.auth.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only "Activity" tab of the admin user page (V63): the user's latest reviews, community
 * posts/comments, orders, bookings, reports filed and received, and community restrictions.
 * Plain SQL rows (column → value maps) — this is a cross-package overview, not a domain model.
 */
@Service
public class AdminUserActivityService {

    private static final int LIMIT = 20;

    private final JdbcTemplate jdbc;

    public AdminUserActivityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Activity(List<Map<String, Object>> reviews,
                           List<Map<String, Object>> posts,
                           List<Map<String, Object>> comments,
                           List<Map<String, Object>> orders,
                           List<Map<String, Object>> bookings,
                           List<Map<String, Object>> reportsFiled,
                           List<Map<String, Object>> reportsReceived,
                           List<Map<String, Object>> communityRestrictions,
                           Map<String, Object> counts) {
    }

    public Activity forUser(User user) {
        UUID id = user.getId();
        return new Activity(
                jdbc.queryForList("""
                        SELECT r.id, r.business_id, b.name AS business_name, r.rating, r.content,
                               r.visibility_status, r.created_at, r.deleted_at
                        FROM review r LEFT JOIN business b ON b.id = r.business_id
                        WHERE r.user_id = ? ORDER BY r.created_at DESC LIMIT ?""", id, LIMIT),
                jdbc.queryForList("""
                        SELECT id, title, body, post_type, status, created_at, deleted_at
                        FROM community_post WHERE author_user_id = ? ORDER BY created_at DESC LIMIT ?""", id, LIMIT),
                jdbc.queryForList("""
                        SELECT id, post_id, content, status, created_at
                        FROM community_post_comment WHERE author_user_id = ? ORDER BY created_at DESC LIMIT ?""", id, LIMIT),
                jdbc.queryForList("""
                        SELECT o.id, o.order_number, o.status, o.total_amount, o.created_at, o.business_id, b.name AS business_name
                        FROM business_order o LEFT JOIN business b ON b.id = o.business_id
                        WHERE o.customer_user_id = ? ORDER BY o.created_at DESC LIMIT ?""", id, LIMIT),
                jdbc.queryForList("""
                        SELECT k.id, k.status, k.slot_start, k.created_at, k.business_id, b.name AS business_name
                        FROM business_booking k LEFT JOIN business b ON b.id = k.business_id
                        WHERE k.customer_user_id = ? ORDER BY k.created_at DESC LIMIT ?""", id, LIMIT),
                jdbc.queryForList("""
                        SELECT id, reference_code, target_type, target_id, reason, status, created_at
                        FROM report WHERE reporter_user_id = ? ORDER BY created_at DESC LIMIT ?""", id, LIMIT),
                jdbc.queryForList("""
                        SELECT rp.id, rp.reference_code, rp.target_type, rp.target_id, rp.reason, rp.status, rp.created_at
                        FROM report rp
                        WHERE (rp.target_type = 'REVIEW' AND rp.target_id IN (SELECT id FROM review WHERE user_id = ?))
                           OR (rp.target_type = 'COMMUNITY_POST' AND rp.target_id IN (SELECT id FROM community_post WHERE author_user_id = ?))
                           OR (rp.target_type = 'COMMUNITY_COMMENT' AND rp.target_id IN (SELECT id FROM community_post_comment WHERE author_user_id = ?))
                           OR (rp.target_type = 'COMMUNITY_PROFILE' AND rp.target_id = ?)
                           OR (rp.target_type = 'LISTING' AND rp.target_id IN (SELECT id FROM business WHERE owner_user_id = ?))
                        ORDER BY rp.created_at DESC LIMIT ?""", id, id, id, user.getCommunityProfileId(), id, LIMIT),
                jdbc.queryForList("""
                        SELECT id, type, status, reason, starts_at, ends_at, created_at
                        FROM community_restriction WHERE user_id = ? ORDER BY created_at DESC LIMIT ?""", id, LIMIT),
                jdbc.queryForMap("""
                        SELECT (SELECT count(*) FROM review WHERE user_id = ?) AS reviews,
                               (SELECT count(*) FROM community_post WHERE author_user_id = ?) AS posts,
                               (SELECT count(*) FROM community_post_comment WHERE author_user_id = ?) AS comments,
                               (SELECT count(*) FROM business_order WHERE customer_user_id = ?) AS orders,
                               (SELECT count(*) FROM business_booking WHERE customer_user_id = ?) AS bookings,
                               (SELECT count(*) FROM report WHERE reporter_user_id = ?) AS reports_filed""",
                        id, id, id, id, id, id));
    }
}
