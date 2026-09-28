package com.example.runtime.archive.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** @param after курсор {@code next} предыдущей страницы; null — первая страница */
public record ReplayRequest(@NotEmpty List<String> tags, @NotNull Instant from, @NotNull Instant to, String after) {
}
