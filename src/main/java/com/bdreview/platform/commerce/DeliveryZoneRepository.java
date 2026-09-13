package com.bdreview.platform.commerce;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DeliveryZoneRepository extends JpaRepository<DeliveryZone, UUID> {

    List<DeliveryZone> findByBusinessIdOrderBySortOrderAsc(UUID businessId);

    List<DeliveryZone> findByBusinessIdAndActiveTrueOrderByMinDistanceKmAsc(UUID businessId);

    long countByBusinessId(UUID businessId);

    boolean existsByBusinessIdAndActiveTrue(UUID businessId);

    void deleteByIdAndBusinessId(UUID id, UUID businessId);

    /** Great-circle distance in metres from the business to (lat,lng), via the GiST-indexed geography column. */
    @Query(value = """
            SELECT ST_Distance(b.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
            FROM business b
            WHERE b.id = :businessId AND b.deleted_at IS NULL
            """, nativeQuery = true)
    Double distanceMetres(@Param("businessId") UUID businessId,
                          @Param("lat") double lat,
                          @Param("lng") double lng);
}
