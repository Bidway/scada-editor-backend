package com.example.scadaeditorbackend.controller;

import com.example.scadaeditorbackend.dto.CreateParamDTO;
import com.example.scadaeditorbackend.dto.KeyValue;
import com.example.scadaeditorbackend.dto.ParamDTO;
import com.example.scadaeditorbackend.service.ParamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/param")
@RequiredArgsConstructor
public class ParamController {
    private final ParamService paramService;

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteParam(@PathVariable Long id) {
        paramService.deleteParamById(id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("")
    public ResponseEntity<ParamDTO> createParam(@RequestBody CreateParamDTO createParamDTO) {
        ParamDTO response = paramService.createParam(createParamDTO);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/update")
    public ResponseEntity updateNodeParams(@RequestBody List<KeyValue> keyValues) {
        return ResponseEntity.ok(paramService.updateNodeParams(keyValues));
    }
}
