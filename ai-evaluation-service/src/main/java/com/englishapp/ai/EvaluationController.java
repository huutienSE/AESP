package com.englishapp.ai;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
public class EvaluationController {
    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);
    private static final Pattern REQUEST_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final GeminiEvaluator evaluator;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping("/internal/evaluations")
    public ResponseEntity<EvaluationContract.Response> evaluate(
            @Valid @RequestBody EvaluationContract.Request request,
            @RequestHeader(value = "X-Request-Id", required = false) String suppliedId
    ) {
        String requestId = acceptedRequestId(suppliedId);
        EvaluationContract.Response response =
                evaluator.evaluate(request.question(), request.answer(), requestId);
        return ResponseEntity.ok()
                .header("X-Request-Id", requestId)
                .body(response);
    }

    @ExceptionHandler(GeminiEvaluator.EvaluationUnavailableException.class)
    public ResponseEntity<EvaluationContract.Error> unavailable(
            GeminiEvaluator.EvaluationUnavailableException ex,
            HttpServletRequest request
    ) {
        String requestId = responseRequestId(request);
        HttpStatus status = "GEMINI_QUOTA".equals(ex.code())
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return error(status, ex.code(), "AI evaluation is temporarily unavailable.", requestId);
    }

    @ExceptionHandler({
            InvalidRequestIdException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<EvaluationContract.Error> invalidRequest(
            Exception ex,
            HttpServletRequest request
    ) {
        String requestId = responseRequestId(request);
        log.warn("AI request rejected requestId={} exceptionType={}",
                requestId, ex.getClass().getSimpleName());
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Question, answer, or request ID is invalid.", requestId);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<EvaluationContract.Error> unexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        String requestId = responseRequestId(request);
        log.error("AI request failed requestId={} exceptionType={}",
                requestId, ex.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "AI evaluation failed.", requestId);
    }

    private static ResponseEntity<EvaluationContract.Error> error(
            HttpStatus status, String code, String message, String requestId
    ) {
        return ResponseEntity.status(status)
                .header("X-Request-Id", requestId)
                .header("X-AI-Error-Code", code)
                .body(new EvaluationContract.Error(code, message, requestId));
    }

    private static String acceptedRequestId(String suppliedId) {
        if (suppliedId == null) {
            return UUID.randomUUID().toString();
        }
        if (!REQUEST_ID.matcher(suppliedId).matches()) {
            throw new InvalidRequestIdException();
        }
        return suppliedId.toLowerCase(Locale.ROOT);
    }

    private static String responseRequestId(HttpServletRequest request) {
        String suppliedId = request.getHeader("X-Request-Id");
        return suppliedId != null && REQUEST_ID.matcher(suppliedId).matches()
                ? suppliedId.toLowerCase(Locale.ROOT)
                : UUID.randomUUID().toString();
    }

    private static class InvalidRequestIdException extends RuntimeException {}
}
