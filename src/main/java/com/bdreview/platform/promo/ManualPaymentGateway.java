package com.bdreview.platform.promo;

import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.promo.PromoEnums.PaymentMethod;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** MVP payment: send money to a merchant number, enter the transaction id, an admin verifies it. */
@Component
public class ManualPaymentGateway implements BoostPaymentGateway {

    /** bKash/Nagad transaction ids are short alphanumeric codes (e.g. "9J7D3K2LQX"). */
    private static final Pattern TRX_ID = Pattern.compile("^[A-Z0-9]{6,30}$");

    private final PromoAccess access;

    public ManualPaymentGateway(PromoAccess access) {
        this.access = access;
    }

    @Override
    public PaymentInstructions instructions(Boost boost) {
        var s = access.settings();
        List<PaymentInstructions.Option> options = new ArrayList<>();
        if (s.getBkashNumber() != null && !s.getBkashNumber().isBlank()) {
            options.add(new PaymentInstructions.Option("BKASH", s.getBkashNumber().trim()));
        }
        if (s.getNagadNumber() != null && !s.getNagadNumber().isBlank()) {
            options.add(new PaymentInstructions.Option("NAGAD", s.getNagadNumber().trim()));
        }
        return new PaymentInstructions(boost.getPriceBdt(), "BDT", options,
                "Send exactly this amount with \"Send Money\" or \"Payment\", then enter the transaction ID. "
                        + "Your boost starts after we confirm the payment.");
    }

    @Override
    public void submit(Boost boost, PaymentMethod method, String reference) {
        if (method == null || method == PaymentMethod.MANUAL) {
            throw new BadRequestException("Choose bKash or Nagad.");
        }
        boolean offered = instructions(boost).options().stream().anyMatch(o -> o.method().equals(method.name()));
        if (!offered) {
            throw new BadRequestException(method.name().charAt(0) + method.name().substring(1).toLowerCase(Locale.ROOT)
                    + " payments aren't set up yet.");
        }
        String ref = reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
        if (!TRX_ID.matcher(ref).matches()) {
            throw new BadRequestException("Enter the transaction ID from your payment SMS (letters and numbers only).");
        }
        boost.setPaymentMethod(method);
        boost.setPaymentRef(ref);
        boost.setPaymentSubmittedAt(Instant.now());
    }

    @Override
    public boolean requiresManualVerification() {
        return true;
    }
}
