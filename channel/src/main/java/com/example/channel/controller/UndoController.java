package com.example.channel.controller;

import com.example.channel.config.command.CommandLog;
import com.example.channel.service.UndoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

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

    @PostMapping
    public List<Long> undo(
            @RequestBody List<Long> logIds,
            @RequestParam Long userId
    ) {
        return undoService.undoLogs(logIds, userId);
    }
}
