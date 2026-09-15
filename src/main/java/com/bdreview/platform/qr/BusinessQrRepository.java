package com.bdreview.platform.qr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BusinessQrRepository extends JpaRepository<BusinessQr, UUID> {

    Optional<BusinessQr> findByBusinessId(UUID businessId);

    Optional<BusinessQr> findByQrTokenAndStatus(String qrToken, QrStatus status);

    boolean existsByQrToken(String qrToken);
}
