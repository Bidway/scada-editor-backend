package com.example.channel.controller;

import com.example.channel.config.command.CommandLog;
import com.example.channel.service.UndoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/channel/undo")
@RequiredArgsConstructor
public class UndoController {
    private final UndoService undoService;

    @GetMapping("/logs")
    public List<CommandLog> getLogs(
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to
    ) {
        return undoService.getLogsByPeriod(from, to);
    }

    @GetMapping("/batch/{batchId}")
    public List<CommandLog> getBatchLogs(@PathVariable UUID batchId) {
        return undoService.getLogsByBatchId(batchId);
    }

    @PostMapping("/batch/{batchId}")
    public void undoBatch(
            @PathVariable UUID batchId,
            @RequestHeader("X-Username") String userName
    ) {
        undoService.undoBatch(batchId, userName);
    }

    @PostMapping
    public List<Long> undo(
            @RequestBody List<Long> logIds,
            @RequestHeader("X-Username") String userName
    ) {
        return undoService.undoLogs(logIds, userName);
    }
}
