package com.example.scadaeditorbackend.dto;

public record WsEvent<T>(
        String type,
        T payload
) {}