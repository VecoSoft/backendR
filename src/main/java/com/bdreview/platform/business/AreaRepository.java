package com.bdreview.platform.business;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AreaRepository extends JpaRepository<Area, UUID> {
    List<Area> findByCityId(UUID cityId);
    Optional<Area> findByCityIdAndNameIgnoreCase(UUID cityId, String name);

    // Admin panel: area.city is LAZY, and open-in-view is disabled, so any
    // screen that reads a.city.name (business form dropdown, reference-data
    // page) needs city eagerly fetched or it throws LazyInitializationException.
    @Query("SELECT a FROM Area a JOIN FETCH a.city ORDER BY a.name")
    List<Area> findAllWithCity();

    /** Same JOIN FETCH reasoning as findAllWithCity, scoped to a batch of ids — see community.CommunityPostService. */
    @Query("SELECT a FROM Area a JOIN FETCH a.city WHERE a.id IN :ids")
    List<Area> findAllByIdWithCity(@Param("ids") Collection<UUID> ids);
}
