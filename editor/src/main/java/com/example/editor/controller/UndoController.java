package com.example.editor.controller;

import com.example.editor.config.command.CommandLog;
import com.example.editor.service.UndoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/editor/undo")
@RequiredArgsConstructor
public class UndoController {

    private final UndoService undoService;

    @GetMapping("/logs")
    public List<CommandLog> getLogs(
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to) {
        return undoService.getLogsByPeriod(from, to);
    }

    @PostMapping("/batch/{batchId}")
    public void undoBatch(
            @PathVariable UUID batchId,
            @RequestHeader("X-Username") String userName) {
        undoService.undoBatch(batchId, userName);
    }

    @PostMapping
    public List<Long> undo(
            @RequestBody List<Long> logIds,
            @RequestHeader("X-Username") String userName) {
        return undoService.undoLogs(logIds, userName);
    }
}
