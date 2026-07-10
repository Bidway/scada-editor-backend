package com.example.channel.controller;

import com.example.channel.service.LockService;
import com.example.channel.service.NodeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

@RestController
@RequestMapping("/api/channel")
public class LockController {

    private final LockService lockService;
    private final NodeService nodeService;

    public LockController(LockService lockService, NodeService itemService) {
        this.lockService = lockService;
        this.nodeService = itemService;
    }

    @PostMapping("/lock")
    public ResponseEntity<?> lock(@RequestBody ArrayList<String> idNodes,
                                  @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(lockService.tryLock(idNodes, userId));
    }

    @PostMapping("/unlock")
    public ResponseEntity<?> unlock(@RequestBody ArrayList<String> idNodes,
                                    @RequestHeader("X-User-Id") Long userId) {

        return ResponseEntity.ok(lockService.unlock(idNodes, userId));
    }

//    @PutMapping("/{id}")
//    public ResponseEntity<?> update(
//            @PathVariable Long id,
//            @RequestBody NodeDto dto
//    ) {
//        SecurityUser user = (SecurityUser) SecurityContextHolder
//                .getContext().getAuthentication().getPrincipal();
//
//        Long userId = user.getId();
//
//        if (lockService.isLockedByAnother("ITEM", id, userId)) {
//            return ResponseEntity.status(HttpStatus.CONFLICT)
//                    .body("Item locked by another user");
//        }
//
//        return ResponseEntity.ok(nodeService.update(id, dto));
//    }

}

