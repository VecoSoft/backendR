package com.bdreview.platform.promo;

import com.bdreview.platform.promo.PromoEnums.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Boost payment step (V58). The MVP implementation is {@link ManualPaymentGateway}: the owner
 * pays the admin-configured bKash/Nagad merchant number and types the transaction id, and an admin
 * verifies it. A real gateway (SSLCommerz, bKash Checkout API) implements this same interface —
 * {@link #requiresManualVerification()} false and {@link #submit} confirming with the provider —
 * without touching BoostService or the UI flow.
 */
public interface BoostPaymentGateway {

    PaymentInstructions instructions(Boost boost);

    /** Validates and records the owner's payment reference on the boost (does not save it). */
    void submit(Boost boost, PaymentMethod method, String reference);

    /** True while an admin must confirm payments by hand in the admin panel. */
    boolean requiresManualVerification();

    record PaymentInstructions(BigDecimal amount, String currency, List<Option> options, String note) {
        public record Option(String method, String merchantNumber) {
        }
    }
}
