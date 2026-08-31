package com.example.editor.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, message.isBlank() ? "Validation failed" : message);
    }

    /**
     * Тело не разобралось в DTO — например, голый массив прислали туда, где теперь ждут
     * конверт {@code {"components": [...]}}. Без отдельного обработчика этот кейс ловил бы
     * общий {@code Exception.class} ниже и отвечал 500 вместо 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        // getOriginalMessage() — только суть ошибки, без "at [Source: ...]" (путь/оффсет
        // разбора и часто фрагмент самого тела запроса) и без стектрейса.
        String reason = cause instanceof JsonProcessingException jsonEx
                ? jsonEx.getOriginalMessage()
                : cause.getMessage();
        return buildResponse(HttpStatus.BAD_REQUEST, "Malformed request body: " + reason);
    }

    /**
     * Форма ответа здесь своя, не через {@link #buildResponse}: контракт с фронтом обещает
     * {@code error: "version_mismatch"} и оба номера версий, а общий обработчик кладёт в
     * {@code error} reason phrase.
     */
    @ExceptionHandler(VersionMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleVersionMismatch(VersionMismatchException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "version_mismatch");
        body.put("base_version", ex.getBaseVersion());
        body.put("current_version", ex.getCurrentVersion());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Конфликт слияния. Форма намеренно совпадает с {@code version_mismatch} по первым трём
     * полям: контракт обещает фронту один обработчик {@code 409} на оба случая, а различает их
     * поле {@code error}.
     */
    @ExceptionHandler(MergeConflictException.class)
    public ResponseEntity<Map<String, Object>> handleMergeConflict(MergeConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "merge_conflict");
        body.put("base_version", ex.getBaseVersion());
        body.put("current_version", ex.getCurrentVersion());
        body.put("conflicts", ex.getConflicts().stream().map(conflict -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", conflict.kind().name());
            row.put("entity", conflict.entity());
            row.put("path", conflict.path());
            row.put("base", conflict.base());
            row.put("yours", conflict.yours());
            row.put("theirs", conflict.theirs());
            row.put("their_user", ex.getTheirUser());
            return row;
        }).toList());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unhandled exception -> 500", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        ));
    }
}
