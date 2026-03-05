package com.example.channel.dto;

public record WsEvent<T>(
        String type,
        T payload
) {}