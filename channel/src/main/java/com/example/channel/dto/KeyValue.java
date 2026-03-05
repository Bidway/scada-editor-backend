package com.example.channel.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

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