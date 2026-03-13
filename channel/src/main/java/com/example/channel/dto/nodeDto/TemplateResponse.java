package com.example.channel.dto.nodeDto;

import com.example.channel.dto.KeyValue;
import com.example.channel.model.template.Template;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class TemplateResponse {
   List<KeyValue> templates;
}
