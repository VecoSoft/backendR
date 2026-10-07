package com.bdreview.platform.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthEmailCodeRepository extends JpaRepository<AuthEmailCode, UUID> {

    /** The newest code for this address and purpose (only the latest one is ever accepted). */
    Optional<AuthEmailCode> findFirstByEmailAndPurposeOrderByCreatedAtDesc(String email, AuthEmailCode.Purpose purpose);

    long countByEmailAndPurposeAndCreatedAtAfter(String email, AuthEmailCode.Purpose purpose, Instant after);

    long countByRequestIpAndPurposeAndCreatedAtAfter(String requestIp, AuthEmailCode.Purpose purpose, Instant after);

    @Modifying
    @Query("UPDATE AuthEmailCode c SET c.consumedAt = :now WHERE c.userId = :userId AND c.purpose = :purpose AND c.consumedAt IS NULL")
    int consumeAllFor(@Param("userId") UUID userId, @Param("purpose") AuthEmailCode.Purpose purpose, @Param("now") Instant now);
}
