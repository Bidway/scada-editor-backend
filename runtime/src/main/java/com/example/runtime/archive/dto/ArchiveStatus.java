package com.example.runtime.archive.dto;

import java.time.Instant;
import java.util.List;

/**
 * Состояние архива этого экземпляра — чтобы дыру в архиве было видно сразу, а не при разборе аварии.
 *
 * @param dropped  точки, сброшенные из-за переполнения очереди
 * @param rejected точки, которые база отвергла по данным и которые выброшены
 */
public record ArchiveStatus(int queueSize, int queueCapacity, long dropped, long rejected, int pending,
                            Instant lastFlushAt, String lastError,
                            List<String> partitions, long defaultPartitionRows) {
}
