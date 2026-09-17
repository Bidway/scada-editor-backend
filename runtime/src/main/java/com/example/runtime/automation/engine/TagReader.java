package com.example.runtime.automation.engine;

public interface TagReader {

    /** {@code null} — значения тега ещё не было. */
    TagReading read(String tag);
}
