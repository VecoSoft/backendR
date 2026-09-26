package com.bdreview.platform.messaging;

import jakarta.validation.constraints.NotBlank;

public record ReactRequest(@NotBlank String emoji) {
}
