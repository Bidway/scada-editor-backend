package com.example.runtime.archive;

import com.example.runtime.archive.dto.ArchiveStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Архив тегов. Не привязан к проекту: база общая, любой экземпляр отвечает по всему архиву,
 * {@code OwnerForwardingFilter} эти пути не пересылает.
 */
@RestController
@RequestMapping("/api/runtime/archive")
@RequiredArgsConstructor
@Tag(name = "Archive", description = "Архив значений тегов: тренды и воспроизведение")
public class ArchiveController {

    private final ArchiveRecorder recorder;
    private final ArchiveWriter writer;
    private final ArchivePartitionManager partitions;

    @Operation(summary = "Состояние архива этого экземпляра: очередь, потери, последняя запись, секции")
    @GetMapping("/status")
    public ArchiveStatus status() {
        return new ArchiveStatus(recorder.queue().size(), recorder.queue().capacity(), recorder.queue().dropped(),
                writer.pendingSize(), writer.lastFlushAt(), writer.lastError(),
                partitions.partitions(ArchivePartitionManager.TAG_ARCHIVE),
                partitions.defaultRows(ArchivePartitionManager.TAG_ARCHIVE));
    }
}
