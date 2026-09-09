package com.example.runtime.dto;

/**
 * Подсказка вероятного текущего шага после сбоя runtime — только предположение,
 * ничего не меняет; окончательное решение (в том числе не соглашаться с догадкой)
 * остаётся за оператором через {@code jump}.
 */
public record ProcedureResumeGuessDto(int suggestedStepIndex) {
}
