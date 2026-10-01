package com.bdreview.platform.community;

import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Community search (V60) — posts and people.
 *
 * <p>Posts: only what the feed would show anyone (not deleted, moderation status ACTIVE, inside
 * the visibility window). Every word must appear in the title or body (Bangla and English alike,
 * case-insensitive); the whole phrase in the title ranks first, then in the body, then newest.
 *
 * <p>People: matches the pseudonymous community username ONLY — never the private account name,
 * so search can't be used to deanonymize anyone. Banned members are left out. Exact match first,
 * then usernames starting with the query, then shorter names.
 */
@Service
public class CommunitySearchService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_TERMS = 6;

    private final NamedParameterJdbcTemplate jdbc;
    private final CommunityPostRepository postRepository;
    private final CommunityPostService postService;

    public CommunitySearchService(NamedParameterJdbcTemplate jdbc, CommunityPostRepository postRepository,
                                  CommunityPostService postService) {
        this.jdbc = jdbc;
        this.postRepository = postRepository;
        this.postService = postService;
    }

    @Transactional(readOnly = true)
    public PageResponse<CommunityPostResponse> searchPosts(String rawQuery, int page, int size, UUID viewerUserId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        String query = normalize(rawQuery);
        if (query == null) {
            return postService.postPage(Page.empty(pageable), viewerUserId);
        }
        List<String> terms = terms(query);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("phrase", "%" + escapeLike(query) + "%")
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            where.append(" AND (COALESCE(p.title, '') || ' ' || p.body) ILIKE :t").append(i).append(" ESCAPE '\\'");
            params.addValue("t" + i, "%" + escapeLike(terms.get(i)) + "%");
        }
        String sql = """
                SELECT p.id, COUNT(*) OVER () AS total
                FROM community_post p
                WHERE p.deleted_at IS NULL
                  AND p.status = 'ACTIVE'
                  AND (p.visible_from IS NULL OR p.visible_from <= now())
                  AND (p.visible_until IS NULL OR p.visible_until > now())
                """ + where + """

                ORDER BY (CASE WHEN p.title ILIKE :phrase ESCAPE '\\' THEN 2 ELSE 0 END
                        + CASE WHEN p.body ILIKE :phrase ESCAPE '\\' THEN 1 ELSE 0 END) DESC,
                         p.created_at DESC
                LIMIT :limit OFFSET :offset
                """;
        List<Row> rows = jdbc.query(sql, params, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getLong("total")));
        List<UUID> ids = rows.stream().map(Row::id).toList();
        Map<UUID, CommunityPost> byId = postRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(CommunityPost::getId, Function.identity()));
        List<CommunityPost> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        return postService.postPage(new PageImpl<>(ordered, pageable, total(rows, pageable)), viewerUserId);
    }

    @Transactional(readOnly = true)
    public PageResponse<CommunityFollowListItem> searchPeople(String rawQuery, int page, int size, UUID viewerUserId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        String query = normalize(stripHandlePrefix(rawQuery));
        if (query == null) {
            return postService.peoplePage(Page.empty(pageable), viewerUserId);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("exact", query)
                .addValue("prefix", escapeLike(query) + "%")
                .addValue("contains", "%" + escapeLike(query) + "%")
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        String sql = """
                SELECT u.id, COUNT(*) OVER () AS total
                FROM app_user u
                WHERE u.community_username ILIKE :contains ESCAPE '\\'
                  AND NOT EXISTS (
                      SELECT 1 FROM community_restriction r
                      WHERE r.user_id = u.id AND r.type = 'BAN' AND r.status = 'ACTIVE'
                        AND (r.ends_at IS NULL OR r.ends_at > now()))
                ORDER BY CASE WHEN LOWER(u.community_username) = LOWER(:exact) THEN 0
                              WHEN u.community_username ILIKE :prefix ESCAPE '\\' THEN 1
                              ELSE 2 END,
                         LENGTH(u.community_username), LOWER(u.community_username)
                LIMIT :limit OFFSET :offset
                """;
        List<Row> rows = jdbc.query(sql, params, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getLong("total")));
        List<UUID> ids = rows.stream().map(Row::id).toList();
        return postService.peoplePage(new PageImpl<>(ids, pageable, total(rows, pageable)), viewerUserId);
    }

    private record Row(UUID id, long total) {
    }

    private static long total(List<Row> rows, Pageable pageable) {
        // COUNT(*) OVER () is the full match count; an out-of-range page has no rows to read it from.
        return rows.isEmpty() ? pageable.getOffset() : rows.get(0).total();
    }

    /** Trimmed, whitespace-collapsed, capped; null when too short to search. */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String q = raw.strip().replaceAll("\\s+", " ");
        if (q.length() > MAX_QUERY_LENGTH) {
            q = q.substring(0, MAX_QUERY_LENGTH).strip();
        }
        return q.codePointCount(0, q.length()) < MIN_QUERY_LENGTH ? null : q;
    }

    /** People search accepts "u/name" and "@name" as typed from posts. */
    static String stripHandlePrefix(String raw) {
        if (raw == null) {
            return null;
        }
        String q = raw.strip();
        if (q.regionMatches(true, 0, "u/", 0, 2)) {
            return q.substring(2);
        }
        return q.startsWith("@") ? q.substring(1) : q;
    }

    static List<String> terms(String query) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(Arrays.asList(query.split(" ")));
        return unique.stream().filter(t -> !t.isBlank()).limit(MAX_TERMS).toList();
    }

    /** Escapes LIKE wildcards so "50%" or "a_b" match literally. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
