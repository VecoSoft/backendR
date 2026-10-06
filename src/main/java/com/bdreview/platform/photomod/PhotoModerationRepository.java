package com.bdreview.platform.photomod;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PhotoModerationRepository extends JpaRepository<PhotoModeration, UUID> {

    @Query("""
            SELECT p FROM PhotoModeration p
            WHERE p.status = :status AND (:source IS NULL OR p.sourceType = :source)
            ORDER BY p.createdAt ASC
            """)
    Page<PhotoModeration> queue(@Param("status") PhotoStatus status, @Param("source") PhotoSource source, Pageable pageable);

    long countByStatus(PhotoStatus status);

    List<PhotoModeration> findBySourceTypeAndSourceIdAndUrlOrderByCreatedAtDesc(PhotoSource sourceType, UUID sourceId, String url);

    List<PhotoModeration> findBySourceTypeAndSourceIdAndStatus(PhotoSource sourceType, UUID sourceId, PhotoStatus status);

    List<PhotoModeration> findByBusinessIdAndStatusOrderByCreatedAtDesc(UUID businessId, PhotoStatus status);

    @Query("""
            SELECT p FROM PhotoModeration p
            WHERE p.uploaderUserId = :uploader AND p.status IN :statuses AND p.createdAt >= :since
            ORDER BY p.createdAt DESC
            """)
    List<PhotoModeration> mine(@Param("uploader") UUID uploader, @Param("statuses") Collection<PhotoStatus> statuses,
                               @Param("since") Instant since);

    /** Every queue row behind one storage object (normally one) — drives who may fetch the file. */
    List<PhotoModeration> findByObjectKey(String objectKey);
}
