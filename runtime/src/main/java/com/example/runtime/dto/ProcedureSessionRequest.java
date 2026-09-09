package com.example.runtime.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProcedureSessionRequest {
    @NotBlank
    private String sessionId;
}
