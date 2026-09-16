package com.example.runtime.exception;

import com.example.runtime.recipe.ProcedureAlreadyRunningException;
import com.example.runtime.recipe.ProcedureStepMismatchException;
import com.example.runtime.recipe.ProjectNotInOperationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;

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
