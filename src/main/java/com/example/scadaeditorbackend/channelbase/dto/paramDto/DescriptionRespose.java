package com.example.scadaeditorbackend.channelbase.dto.paramDto;

import com.example.scadaeditorbackend.channelbase.model.Description;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DescriptionRespose {
    private List<Description> descriptions = new ArrayList<>();
}
