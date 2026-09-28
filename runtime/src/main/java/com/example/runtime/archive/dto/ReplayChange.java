package com.example.runtime.archive.dto;

import java.time.Instant;

public record ReplayChange(Instant ts, String tag, Object value, boolean good) {
}
