package com.example.scadaeditorbackend.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodeTemplates {
    private static final Map<String, List<Long>> TEMPLATES = new HashMap<>();

    static {
        // Шаблоны параметров (ID из таблицы description)
        TEMPLATES.put("dev", List.of(4L,5L,6L, 7L, 8L,1L,2L,3L,9L,10L,11L,12L,13L));
        TEMPLATES.put("sub", List.of(6L, 9L,1L));
        TEMPLATES.put("cha", List.of(6L, 15L, 16L, 17L,8L,1L));
    }

    public static List<Long> getTemplateParams(String nodeType) {
        return TEMPLATES.get(nodeType);
    }
}
