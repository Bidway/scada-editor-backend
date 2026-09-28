package com.example.runtime.archive.dto;

import java.time.Instant;
import java.util.List;

public record ArchiveValuesResponse(Instant from, Instant to, List<ArchiveSeries> series) {
}
