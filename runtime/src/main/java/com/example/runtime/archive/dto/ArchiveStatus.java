package com.example.runtime.archive.dto;

import java.time.Instant;
import java.util.List;

/** Состояние архива этого экземпляра — чтобы дыру в архиве было видно сразу, а не при разборе аварии. */
public record ArchiveStatus(int queueSize, int queueCapacity, long dropped, int pending,
                            Instant lastFlushAt, String lastError,
                            List<String> partitions, long defaultPartitionRows) {
}
