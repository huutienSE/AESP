package com.englishapp.ai;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiEvaluator {
    private static final Logger log = LoggerFactory.getLogger(GeminiEvaluator.class);
    private static final List<String> MODELS =
            List.of("gemini-3.1-flash-lite", "gemini-2.5-flash-lite");
    private static final Pattern SCORE = Pattern.compile("SCORE:\\s*(\\d+)");
    private static final Pattern FEEDBACK = Pattern.compile("FEEDBACK:\\s*(.*)", Pattern.DOTALL);

    private final Client client;

    public GeminiEvaluator(@Value("${AESP_GEMINI_API_KEY}") String apiKey) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(35))
                .retryOnConnectionFailure(false)
                .build();

        this.client = Client.builder()
                .apiKey(apiKey)
                .clientOptions(ClientOptions.builder()
                        .customHttpClient(httpClient)
                        .build())
                .httpOptions(HttpOptions.builder()
                        .timeout(30_000)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
    }

    public EvaluationContract.Response evaluate(String question, String answer, String requestId) {
        long totalStarted = System.nanoTime();
        boolean quotaSeen = false;

        for (String model : MODELS) {
            long attemptStarted = System.nanoTime();
            try {
                log.info("AI evaluation started requestId={} model={}", requestId, model);
                String prompt = """
                        You are an IELTS speaking examiner.

                        Question:
                        %s

                        User Answer:
                        %s

                        Evaluate the answer carefully.

                        Score must be between 0 and 100.

                        Return EXACTLY in this format:

                        SCORE: number
                        FEEDBACK: short feedback only
                        """.formatted(question, answer);

                GenerateContentResponse response =
                        client.models.generateContent(model, prompt, null);
                EvaluationContract.Response parsed = parse(response.text(), model);
                log.info("AI evaluation succeeded requestId={} model={} attemptMs={} totalMs={}",
                        requestId, model, elapsedMs(attemptStarted), elapsedMs(totalStarted));
                return parsed;
            } catch (ApiException ex) {
                quotaSeen |= ex.code() == 429;
                log.warn("AI provider failed requestId={} model={} providerStatus={} attemptMs={}",
                        requestId, model, ex.code(), elapsedMs(attemptStarted));
            } catch (RuntimeException ex) {
                log.warn("AI evaluation failed requestId={} model={} exceptionType={} attemptMs={}",
                        requestId, model, ex.getClass().getSimpleName(), elapsedMs(attemptStarted));
            }
        }

        log.error("AI evaluation exhausted requestId={} totalMs={} quotaSeen={}",
                requestId, elapsedMs(totalStarted), quotaSeen);
        throw new EvaluationUnavailableException(
                quotaSeen ? "GEMINI_QUOTA" : "GEMINI_UNAVAILABLE");
    }

    private static EvaluationContract.Response parse(String text, String model) {
        if (text == null) {
            throw new IllegalArgumentException("Empty model response");
        }
        Matcher scoreMatch = SCORE.matcher(text);
        Matcher feedbackMatch = FEEDBACK.matcher(text);
        if (!scoreMatch.find() || !feedbackMatch.find()) {
            throw new IllegalArgumentException("Invalid model response");
        }
        String feedback = feedbackMatch.group(1).trim();
        if (feedback.isBlank() || feedback.length() > 4000) {
            throw new IllegalArgumentException("Invalid feedback length");
        }
        int score = Math.clamp(Integer.parseInt(scoreMatch.group(1)), 0, 100);
        return new EvaluationContract.Response(score, feedback, model);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public static class EvaluationUnavailableException extends RuntimeException {
        private final String code;

        public EvaluationUnavailableException(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
