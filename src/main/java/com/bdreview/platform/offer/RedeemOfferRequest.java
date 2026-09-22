package com.bdreview.platform.offer;

import jakarta.validation.constraints.NotBlank;

public record RedeemOfferRequest(@NotBlank String redemptionCode) {
}
