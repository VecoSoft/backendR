package com.bdreview.platform.admin.support;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Template helper — {@code ${@fmt.dt(instant)}} — Bangladesh-time formatting for the admin panel. */
@Component("fmt")
public class AdminFormat {

    private static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZONE);

    public String dt(Instant instant) {
        return instant == null ? "—" : DT.format(instant);
    }

    /** Value for an {@code <input type="datetime-local">}. */
    public String input(Instant instant) {
        return instant == null ? "" : INPUT.format(instant);
    }

    /** "3d", "5h", "12m" — account age / time since. */
    public String age(Instant instant) {
        if (instant == null) {
            return "—";
        }
        Duration d = Duration.between(instant, Instant.now());
        if (d.toDays() >= 365) {
            return (d.toDays() / 365) + "y";
        }
        if (d.toDays() >= 1) {
            return d.toDays() + "d";
        }
        if (d.toHours() >= 1) {
            return d.toHours() + "h";
        }
        return Math.max(0, d.toMinutes()) + "m";
    }

    /** One entry per line, for textareas. */
    public String lines(java.util.List<String> values) {
        return values == null ? "" : String.join("\n", values);
    }

    public String snippet(String title, String body, int max) {
        String s = title != null && !title.isBlank() ? title : body;
        if (s == null || s.isBlank()) {
            return "(no text)";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
