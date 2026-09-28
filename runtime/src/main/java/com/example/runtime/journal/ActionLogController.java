package com.example.runtime.journal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/runtime/actions")
@RequiredArgsConstructor
@Tag(name = "Actions", description = "Журнал действий оператора")
public class ActionLogController {

    private final ActionLogReader reader;

    @Operation(summary = "Действия оператора за период, новые сверху")
    @GetMapping
    public List<ActionRecord> find(@RequestParam(required = false) Long projectId,
                                   @RequestParam Instant from,
                                   @RequestParam Instant to,
                                   @RequestParam(required = false) String username,
                                   @RequestParam(required = false) String kind,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "100") int size) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Период задан неверно: нужно from < to");
        }
        return reader.find(projectId, from, to, username, kind, Math.max(page, 0), Math.min(Math.max(size, 1), 1000));
    }
}
