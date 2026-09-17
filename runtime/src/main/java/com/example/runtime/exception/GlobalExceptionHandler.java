package com.example.runtime.exception;

import com.example.runtime.assignment.AssignmentConflictException;
import com.example.runtime.recipe.ProcedureAlreadyRunningException;
import com.example.runtime.recipe.ProcedureStepMismatchException;
import com.example.runtime.recipe.ProjectNotInOperationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamNotFound(HttpClientErrorException.NotFound ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "Referenced project/tag not found in editor/channel");
    }

    /**
     * 409, а не 400: запрос корректен, но состояние не позволяет его выполнить. В теле —
     * текущий статус процедуры, чтобы оператор увидел, на каком шаге мойка, вместо того чтобы
     * её сбить повторным запуском.
     */
    @ExceptionHandler(ProcedureAlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyRunning(ProcedureAlreadyRunningException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("timestamp", LocalDateTime.now(), "status", HttpStatus.CONFLICT.value(),
                        "error", HttpStatus.CONFLICT.getReasonPhrase(), "message", ex.getMessage(),
                        "procedure", ex.status()));
    }

    @ExceptionHandler(ProcedureStepMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleStepMismatch(ProcedureStepMismatchException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("timestamp", LocalDateTime.now(), "status", HttpStatus.CONFLICT.value(),
                        "error", HttpStatus.CONFLICT.getReasonPhrase(), "message", ex.getMessage(),
                        "procedure", ex.status()));
    }

    @ExceptionHandler(ProjectNotInOperationException.class)
    public ResponseEntity<Map<String, Object>> handleNotInOperation(ProjectNotInOperationException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 409: назначение противоречит уже существующим; в теле — непокрытые пути, если дело в них. */
    @ExceptionHandler(AssignmentConflictException.class)
    public ResponseEntity<Map<String, Object>> handleAssignmentConflict(AssignmentConflictException ex) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", HttpStatus.CONFLICT.getReasonPhrase());
        body.put("message", ex.getMessage());
        if (!ex.uncoveredPaths().isEmpty()) {
            body.put("uncoveredPaths", ex.uncoveredPaths());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Гонка двух назначений: второе упёрлось в первичный или уникальный ключ. */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        return buildResponse(HttpStatus.CONFLICT, "Назначение уже занято другим экземпляром — обновите список и повторите");
    }

    /** Явный статус из сервиса, например 404 на неизвестный экземпляр. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        return buildResponse(HttpStatus.valueOf(ex.getStatusCode().value()),
                ex.getReason() == null ? "" : ex.getReason());
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
     * Ошибки запроса, которые Spring MVC бросает до контроллера. Без явного маппинга их ловил
     * обработчик {@code Exception} ниже: клиент получал безликий 500, а лог — стектрейс уровня
     * ERROR на обычную опечатку в запросе.
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception ex) {
        String message = ex instanceof MissingServletRequestParameterException missing
                ? "Не передан параметр " + missing.getParameterName()
                : ex instanceof MethodArgumentTypeMismatchException mismatch
                        ? "Неверное значение параметра " + mismatch.getName()
                        : "Тело запроса не читается как JSON";
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    /** Неизвестный путь, в том числе удалённый {@code resume-guess}. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "Нет такого эндпоинта: /" + ex.getResourcePath());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
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
