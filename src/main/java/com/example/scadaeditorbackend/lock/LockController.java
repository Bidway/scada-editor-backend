package com.example.scadaeditorbackend.lock;

import com.example.scadaeditorbackend.dto.nodeDto.NodeDto;
import com.example.scadaeditorbackend.security.CurrentUserService;
import com.example.scadaeditorbackend.security.SecurityUser;
import com.example.scadaeditorbackend.service.NodeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

@RestController
@RequestMapping("/api")
public class LockController {

    private final LockService lockService;
    private final NodeService nodeService;
    private final CurrentUserService currentUserService;

    public LockController(LockService lockService, NodeService itemService, CurrentUserService currentUserService) {
        this.lockService = lockService;
        this.nodeService = itemService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/lock")
    public ResponseEntity<?> lock(@RequestBody ArrayList<String> idNodes, Authentication auth) {
        return ResponseEntity.ok(lockService.tryLock(idNodes, auth));
    }

    @PostMapping("/unlock")
    public ResponseEntity<?> unlock(@RequestBody ArrayList<String> idNodes, Authentication auth) {

        return ResponseEntity.ok(lockService.unlock(idNodes, auth));
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

