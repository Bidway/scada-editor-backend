package com.example.runtime.stream;

public record PropertyUpdate(Long propertyId, String propertyName, Object value, long ts) {
}
