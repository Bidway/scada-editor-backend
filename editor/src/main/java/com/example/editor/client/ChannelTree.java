package com.example.editor.client;

import java.util.List;

/**
 * Поддерево базы каналов, как его отдаёт channel ({@code fullHierarchy}): пути узлов и их
 * параметры. Параметр адресует узел путём, имя — имя типа («Имя в ПЛК», «Описание»).
 */
public record ChannelTree(List<String> nodes, List<Param> params) {

    public record Param(String node, String name, String value) {
    }
}
