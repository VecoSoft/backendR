package com.bdreview.platform.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The business account has no password of its own (V70): it is opened through the personal account. {@code password} is ignored. */
public record RegisterBusinessRequest(
        String password,
        @NotBlank @Size(max = 120) String name) {
}
