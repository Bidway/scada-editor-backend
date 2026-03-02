package com.example.scadaeditorbackend.channelbase.dto;

public record WsEvent<T>(
        String type,
        T payload
) {}