package com.example.channel.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class UndoBatchDto {
    private UUID batchId;
}
