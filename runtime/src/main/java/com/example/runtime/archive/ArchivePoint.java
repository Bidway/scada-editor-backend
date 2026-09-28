package com.example.runtime.archive;

/**
 * Точка архива на пути к БД. Ровно одно из {@code num}/{@code text} заполнено у достоверной
 * точки; у недостоверной ({@code good=false}) оба null.
 *
 * @param tag путь тега (внутренний id подставляет писатель)
 * @param ts  момент приёма по часам runtime, epoch ms; у тега уникален
 */
public record ArchivePoint(String tag, long ts, Double num, String text, boolean good) {
}
