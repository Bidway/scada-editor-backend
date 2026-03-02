package com.example.scadaeditorbackend.channelbase.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.List;

@Data
@NoArgsConstructor
public class KeyValue {
    private Long key;
    private String value;

    public KeyValue(Long key, String value) {
        this.key = key;
        this.value = value;
    }
}