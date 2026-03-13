package com.example.channel.controller;

import com.example.channel.dto.paramDto.CreateParamDto;
import com.example.channel.dto.KeyValue;
import com.example.channel.dto.paramDto.DescriptionResponse;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.service.ParamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/channel/param")
@RequiredArgsConstructor
public class ParamController {
    private final ParamService paramService;

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteParam(@PathVariable Long id) {
        paramService.deleteParamById(id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("")
    public ResponseEntity<ParamDto> createParam(@RequestBody CreateParamDto createParamDTO) {
        ParamDto response = paramService.createParam(createParamDTO);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/update")
    public ResponseEntity updateNodeParams(@RequestBody List<KeyValue> keyValues) {
        return ResponseEntity.ok(paramService.updateParams(keyValues));
    }
    @GetMapping("/description")
    public ResponseEntity<DescriptionResponse> getDescriptions(){
        return ResponseEntity.ok(paramService.getDescriptions());
    }
}
