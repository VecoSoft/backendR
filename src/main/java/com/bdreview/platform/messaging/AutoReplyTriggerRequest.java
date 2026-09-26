package com.bdreview.platform.messaging;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Never freeform content — the auto-reply's answer text is always looked up
 *  server-side by id, so a customer can't forge an arbitrary "owner" message. */
public record AutoReplyTriggerRequest(@NotNull UUID autoReplyId) {
}
