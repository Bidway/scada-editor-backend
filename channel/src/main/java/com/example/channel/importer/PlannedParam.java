package com.example.channel.importer;

/** Параметр к вставке: узел задан путём, как в channel.param.id_node. */
public record PlannedParam(String idNode, long idType, String value) {
}
