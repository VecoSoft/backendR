package com.bdreview.platform.auth;

import jakarta.validation.constraints.NotBlank;

public record LinkAccountsRequest(@NotBlank String code) {
}
