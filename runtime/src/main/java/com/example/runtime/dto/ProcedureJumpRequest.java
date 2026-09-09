package com.example.runtime.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProcedureJumpRequest {
    @NotBlank
    private String sessionId;
    @Min(0)
    private int stepIndex;
}
