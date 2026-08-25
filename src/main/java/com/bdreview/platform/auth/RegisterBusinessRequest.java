package com.bdreview.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterBusinessRequest(
        @NotBlank String password,
        @NotBlank @Size(max = 120) String name) {
}
