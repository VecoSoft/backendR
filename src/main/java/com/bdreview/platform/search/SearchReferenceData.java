package com.bdreview.platform.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Small, slowly-changing lookup data every smart search needs (areas, cities, how many live businesses
 * of each category kind sit in each area) — read in three tiny queries and held in memory for
 * {@link #TTL}, so parsing a query and building suggestions never touches the database for it.
 * A newly added area simply becomes searchable within TTL.
 */
@Component
public class SearchReferenceData {

    private static final Duration TTL = Duration.ofMinutes(5);

    /** centroidLat/Lng = centre of that area's live businesses — the anchor for "near Mirpur" fallbacks. */
    public record AreaRef(UUID id, String name, UUID cityId, String cityName, int businessCount,
                          Double centroidLat, Double centroidLng) {
    }

    public record CityRef(UUID id, String name, int businessCount) {
    }

    public record Snapshot(Map<String, AreaRef> areasByLowerName, Map<String, CityRef> citiesByLowerName,
                           Map<String, Integer> businessCountByKind,
                           Map<String, List<AreaRef>> topAreasByKind, List<AreaRef> topAreas,
                           Map<String, String> categoryNameByKind, Instant loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot;

    public SearchReferenceData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Snapshot get() {
        Snapshot s = snapshot;
        if (s == null || s.loadedAt().plus(TTL).isBefore(Instant.now())) {
            synchronized (this) {
                s = snapshot;
                if (s == null || s.loadedAt().plus(TTL).isBefore(Instant.now())) {
                    s = load();
                    snapshot = s;
                }
            }
        }
        return s;
    }

    private Snapshot load() {
        Map<UUID, AreaRef> areasById = new HashMap<>();
        Map<String, AreaRef> areasByName = new HashMap<>();
        jdbc.query("""
                SELECT a.id, a.name, a.city_id, c.name AS city_name, count(b.id) AS n,
                       ST_Y(ST_Centroid(ST_Collect(b.location::geometry))) AS lat,
                       ST_X(ST_Centroid(ST_Collect(b.location::geometry))) AS lng
                FROM area a
                JOIN city c ON c.id = a.city_id
                LEFT JOIN business b ON b.area_id = a.id AND b.deleted_at IS NULL
                GROUP BY a.id, a.name, a.city_id, c.name
                """, rs -> {
            AreaRef ref = new AreaRef(rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getObject("city_id", UUID.class), rs.getString("city_name"), rs.getInt("n"),
                    (Double) rs.getObject("lat"), (Double) rs.getObject("lng"));
            areasById.put(ref.id(), ref);
            // Same area name in two cities: keep whichever has more listings.
            areasByName.merge(ref.name().toLowerCase(), ref,
                    (a, b) -> a.businessCount() >= b.businessCount() ? a : b);
        });

        Map<String, CityRef> citiesByName = new HashMap<>();
        jdbc.query("""
                SELECT c.id, c.name, count(b.id) AS n
                FROM city c LEFT JOIN business b ON b.city_id = c.id AND b.deleted_at IS NULL
                GROUP BY c.id, c.name
                """, rs -> {
            citiesByName.put(rs.getString("name").toLowerCase(),
                    new CityRef(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("n")));
        });

        Map<String, Integer> countByKind = new HashMap<>();
        Map<String, List<AreaRef>> areasByKind = new HashMap<>();
        Map<String, String> categoryNameByKind = new HashMap<>();
        jdbc.query("""
                SELECT cat.kind, b.area_id, count(*) AS n
                FROM business b JOIN category cat ON cat.id = b.category_id
                WHERE b.deleted_at IS NULL
                GROUP BY cat.kind, b.area_id
                ORDER BY n DESC
                """, rs -> {
            String kind = rs.getString("kind");
            countByKind.merge(kind, rs.getInt("n"), Integer::sum);
            AreaRef area = areasById.get(rs.getObject("area_id", UUID.class));
            if (area != null) {
                areasByKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(area);
            }
        });
        jdbc.query("SELECT kind, min(name) AS name FROM category GROUP BY kind",
                rs -> {
                    categoryNameByKind.put(rs.getString("kind"), rs.getString("name"));
                });

        List<AreaRef> topAreas = areasById.values().stream()
                .filter(a -> a.businessCount() > 0)
                .sorted(Comparator.comparingInt(AreaRef::businessCount).reversed())
                .toList();
        return new Snapshot(Map.copyOf(areasByName), Map.copyOf(citiesByName), Map.copyOf(countByKind),
                Map.copyOf(areasByKind), topAreas, Map.copyOf(categoryNameByKind), Instant.now());
    }
}
