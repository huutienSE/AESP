package com.englishapp.service.impl;

import com.englishapp.entity.Feedback;
import com.englishapp.service.AIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Primary
public class AIRouterServiceImpl implements AIService {
    private static final Logger log = LoggerFactory.getLogger(AIRouterServiceImpl.class);
    private final GeminiFlash31LiteServiceImpl geminiFlash31LiteService;
    private final GeminiFlash25LiteServiceImpl geminiFlash25LiteService;

    public AIRouterServiceImpl(
            GeminiFlash31LiteServiceImpl geminiFlash31Service,
            GeminiFlash25LiteServiceImpl geminiFlash25LiteService
    ) {
        this.geminiFlash31LiteService = geminiFlash31Service;
        this.geminiFlash25LiteService = geminiFlash25LiteService;
    }

    @Override
    public Feedback evaluateAnswer(String question, String answer) {
        long requestStarted = System.nanoTime();

        try {
            return evaluateWithTiming("gemini-3.1-flash-lite", requestStarted,
                    () -> geminiFlash31LiteService.evaluateAnswer(question, answer));
        } catch (Exception e) {
            log.warn("Primary AI model failed; trying fallback exceptionType={}",
                    e.getClass().getSimpleName());
        }

        try {
            Feedback feedback = evaluateWithTiming("gemini-2.5-flash-lite", requestStarted,
                    () -> geminiFlash25LiteService.evaluateAnswer(question, answer));
            if (feedback != null && feedback.getFeedbackText() != null
                    && !"AI evaluation failed.".equals(feedback.getFeedbackText())) {
                return feedback;
            }
            log.error("Fallback AI model returned an invalid feedback response");
        } catch (Exception e) {
            log.error("Fallback AI model failed exceptionType={}",
                    e.getClass().getSimpleName());
        }

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI evaluation is temporarily unavailable. Please try again."
        );
    }

    private Feedback evaluateWithTiming(
            String model,
            long requestStarted,
            java.util.function.Supplier<Feedback> evaluation
    ) {
        long attemptStarted = System.nanoTime();
        try {
            log.info("AI evaluation started model={}", model);
            Feedback feedback = evaluation.get();
            log.info("AI evaluation succeeded model={} attemptMs={} totalMs={}",
                    model,
                    (System.nanoTime() - attemptStarted) / 1_000_000,
                    (System.nanoTime() - requestStarted) / 1_000_000);
            return feedback;
        } catch (RuntimeException e) {
            log.warn("AI evaluation failed model={} attemptMs={} exceptionType={}",
                    model,
                    (System.nanoTime() - attemptStarted) / 1_000_000,
                    e.getClass().getSimpleName());
            throw e;
        }
    }
}
