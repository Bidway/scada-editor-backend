package com.example.scadaeditorbackend.lock;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lock")
public class ItemController {

//    private final LockService lockService;
//    private final NodeService nodeService;
//
//    public ItemController(LockService lockService, NodeService itemService) {
//        this.lockService = lockService;
//        this.nodeService = itemService;
//    }
//
//    @PostMapping("/{id}/lock")
//    public ResponseEntity<?> lock(@PathVariable Long id, @RequestHeader("userId") Long userId) {
//        if (lockService.tryLock("ITEM", id, userId)) {
//            return ResponseEntity.ok("Locked");
//        }
//        return ResponseEntity.status(HttpStatus.CONFLICT).body("Already locked");
//    }
//
//    @PostMapping("/{id}/unlock")
//    public ResponseEntity<?> unlock(@PathVariable Long id, @RequestHeader("userId") Long userId) {
//        if (lockService.unlock("ITEM", id, userId)) {
//            return ResponseEntity.ok("Unlocked");
//        }
//        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You do not own lock");
//    }

//    @PutMapping("/{id}")
//    public ResponseEntity<?> update(
//            @PathVariable Long id,
//            @RequestBody NodeDTO dto
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

