package com.example.editor.client;

/** channel не ответил или ответил ошибкой — автопривязка ничего не записала. */
public class ChannelUnavailableException extends RuntimeException {

    public ChannelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
