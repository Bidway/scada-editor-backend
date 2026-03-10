package com.example.channel.controller;

import com.example.channel.service.UndoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/channel/undo")
@RequiredArgsConstructor
public class UndoController {
    private final UndoService undoService;
    @PatchMapping()
    public List<Long> undo(@RequestBody List<Long> idCommandLogs,
                           @RequestHeader("X-User-Id") Long userId) {
        return undoService.undo(idCommandLogs, userId);
    }
}
