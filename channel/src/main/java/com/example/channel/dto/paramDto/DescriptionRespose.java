package com.example.channel.dto.paramDto;

import com.example.channel.model.Description;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DescriptionRespose {
    private List<Description> descriptions = new ArrayList<>();
}
