package com.example.runtime.recipe;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.ResolvedRecipeValue;
import com.example.runtime.dto.ApplyRecipeResult;
import com.example.runtime.kafka.CommandProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Применение рецепта: тянет резолв уставок из editor по {@code recipeId} и пишет каждое значение
 * в его тег через {@link CommandProducer} — тот же путь, что {@code writeTag}: топик команд →
 * драйвер → ПЛК. Значения server-authoritative: оператор передаёт только id рецепта, произвольные
 * уставки с клиента не принимаются.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecipeApplyService {

    private final EditorClient editorClient;
    private final CommandProducer commandProducer;

    public ApplyRecipeResult apply(Long recipeId) {
        List<ResolvedRecipeValue> values = editorClient.getResolvedRecipe(recipeId);
        if (values == null) {
            values = List.of();
        }
        int sent = 0;
        List<String> failedTags = new ArrayList<>();
        for (ResolvedRecipeValue v : values) {
            if (v.tagId() == null || v.tagId().isBlank()) {
                continue;
            }
            boolean ok = commandProducer.send(v.tagId(), coerce(v.value(), v.valueType()));
            if (ok) {
                sent++;
            } else {
                failedTags.add(v.tagId());
            }
        }
        ApplyRecipeResult result = new ApplyRecipeResult(recipeId, values.size(), sent, failedTags.size(), failedTags);
        log.info("Recipe {} applied: {}/{} commands sent, {} failed", recipeId, sent, values.size(), failedTags.size());
        return result;
    }

    /**
     * Строку рецепта приводим к Java-типу по объявленному {@code value_type} строки —
     * {@link CommandProducer} по фактическому типу выведет dataType для драйвера. Непарсящееся
     * под тип значение отдаём строкой (решит драйвер).
     */
    private Object coerce(String value, String valueType) {
        if (value == null) {
            return null;
        }
        if (valueType == null) {
            return value;
        }
        String raw = value.trim();
        try {
            switch (valueType.trim().toLowerCase()) {
                case "number":
                case "double":
                case "float":
                case "real":
                    return Double.parseDouble(raw);
                case "int":
                case "integer":
                case "long":
                    return Long.parseLong(raw);
                case "bool":
                case "boolean":
                    return Boolean.valueOf(raw);
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
