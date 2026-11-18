package com.englishapp.service.impl;

import com.englishapp.entity.Feedback;
import com.englishapp.service.AIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;

@Service
@Primary
@Profile("split") // chỉ tạo bean khi profile bật "split"
public class RemoteAIService implements AIService {
    private static final Logger log = LoggerFactory.getLogger(RemoteAIService.class);
    private final RestClient client;

    public RemoteAIService(@Value("${AESP_AI_SERVICE_URL:http://127.0.0.1:8081}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(90));

        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Feedback evaluateAnswer(String question, String answer) {
        // tạo req ID
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        try {
            log.info("Core AI request started requestId={}", requestId);

            // gọi sang ai evaluation service
            ResponseEntity<EvaluationResponse> result = client.post()
                    .uri("/internal/evaluations")
                    .header("X-Request-Id", requestId)
                    .body(new EvaluationRequest(question, answer))
                    .retrieve()
                    .toEntity(EvaluationResponse.class);

            EvaluationResponse response = result.getBody();
            String echoedId = result.getHeaders().getFirst("X-Request-Id");
            if (!requestId.equals(echoedId)
                    || response == null || response.score() == null
                    || response.score() < 0 || response.score() > 100
                    || response.feedbackText() == null
                    || response.feedbackText().isBlank()
                    || response.feedbackText().length() > 4000
                    || response.model() == null || response.model().isBlank()) {
                log.error("Core AI response invalid requestId={} elapsedMs={}", requestId, elapsedMs(started));

                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid AI response");
            }

            Feedback feedback = new Feedback();
            feedback.setOverallScore(response.score());
            feedback.setFeedbackText(response.feedbackText());
            log.info("Core AI request succeeded requestId={} model={} elapsedMs={}", requestId, response.model(), elapsedMs(started));

            return feedback;
        } catch (RestClientResponseException ex) {
            int upstreamStatus = ex.getStatusCode().value();
            String errorCode = knownErrorCode(ex);

            log.warn("Core AI upstream error requestId={} upstreamStatus={} aiErrorCode={} elapsedMs={}",
                    requestId, upstreamStatus, errorCode, elapsedMs(started));

            HttpStatus status = upstreamStatus == 429 || upstreamStatus == 503
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;

            throw new ResponseStatusException(status, "AI service returned an error");
        } catch (ResourceAccessException ex) {
            boolean timeout = isTimeout(ex);

            log.warn("Core AI transport failed requestId={} failureType={} elapsedMs={}",
                    requestId, timeout ? "CORE_AI_TIMEOUT" : "AI_SERVICE_UNREACHABLE",
                    elapsedMs(started));

            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    timeout ? "AI service timed out" : "AI service is unavailable");
        } catch (RestClientException ex) {
            log.error("Core AI response handling failed requestId={} exceptionType={} elapsedMs={}",
                    requestId, ex.getClass().getSimpleName(), elapsedMs(started));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid AI service response");
        }
    }

    private static String knownErrorCode(RestClientResponseException ex) {
        String raw = ex.getResponseHeaders() == null
                ? null : ex.getResponseHeaders().getFirst("X-AI-Error-Code");
        if (raw == null) {
            return "UNKNOWN";
        }
        return switch (raw) {
            case "GEMINI_QUOTA", "GEMINI_UNAVAILABLE", "INVALID_REQUEST", "INTERNAL_ERROR" -> raw;
            default -> "UNKNOWN";
        };
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public record EvaluationRequest(String question, String answer) {}
    public record EvaluationResponse(Integer score, String feedbackText, String model) {}
}
