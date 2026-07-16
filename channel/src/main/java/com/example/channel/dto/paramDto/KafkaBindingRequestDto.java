package com.example.channel.dto.paramDto;

import lombok.Data;

import java.util.List;

@Data
public class KafkaBindingRequestDto {
    private List<Long> ids;
}
