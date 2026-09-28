package com.example.runtime.archive;

import com.example.runtime.archive.dto.ArchiveStatus;
import com.example.runtime.archive.dto.ArchiveValuesResponse;
import com.example.runtime.archive.dto.ReplayRequest;
import com.example.runtime.archive.dto.ReplayResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

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
    private final ArchiveQueryService query;

    @Operation(summary = "Состояние архива этого экземпляра: очередь, потери, последняя запись, секции")
    @GetMapping("/status")
    public ArchiveStatus status() {
        return new ArchiveStatus(recorder.queue().size(), recorder.queue().capacity(), recorder.queue().dropped(),
                writer.pendingSize(), writer.lastFlushAt(), writer.lastError(),
                partitions.partitions(ArchivePartitionManager.TAG_ARCHIVE),
                partitions.defaultRows(ArchivePartitionManager.TAG_ARCHIVE));
    }

    @Operation(summary = "История тегов для тренда: значение на начало периода и точки в периоде; "
            + "числовые теги прорежены до maxPoints корзинами «минимум/максимум»")
    @GetMapping("/values")
    public ArchiveValuesResponse values(@RequestParam("tag") List<String> tags,
                                        @RequestParam Instant from,
                                        @RequestParam Instant to,
                                        @RequestParam(required = false) Integer maxPoints) {
        return query.values(tags, from, to, maxPoints);
    }

    @Operation(summary = "Воспроизведение: состояние тегов на from и изменения за период страницами. "
            + "POST — тегов сцены сотни, в URL не помещаются")
    @PostMapping("/replay")
    public ReplayResponse replay(@Valid @RequestBody ReplayRequest request) {
        return query.replay(request);
    }
}
