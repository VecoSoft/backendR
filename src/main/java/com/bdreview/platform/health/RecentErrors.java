package com.bdreview.platform.health;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory ring buffer of the last {@value #CAPACITY} server errors (5xx) for System → Health —
 * no external service. Per instance and lost on restart, by design.
 */
@Component
public class RecentErrors {

    public static final int CAPACITY = 100;

    public record ErrorEntry(Instant at, String method, String path, String type, String message) {
    }

    private final Deque<ErrorEntry> buffer = new ArrayDeque<>(CAPACITY);

    public synchronized void record(String method, String path, Throwable error) {
        if (buffer.size() == CAPACITY) {
            buffer.removeLast();
        }
        String message = error.getMessage();
        buffer.addFirst(new ErrorEntry(Instant.now(), method, path, error.getClass().getName(),
                message == null ? "" : message.substring(0, Math.min(message.length(), 500))));
    }

    /** Newest first. */
    public synchronized List<ErrorEntry> latest() {
        return new ArrayList<>(buffer);
    }

    public synchronized void clear() {
        buffer.clear();
    }
}
