package com.example.runtime.archive.dto;

import java.time.Instant;

/** Точка архива наружу. {@code value}: число ({@code Double}, bool — 1/0), строка или null при good=false. */
public record ArchiveValue(Instant ts, Object value, boolean good) {
}
