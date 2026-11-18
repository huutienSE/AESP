package com.englishapp.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class EvaluationContract {
    private EvaluationContract() {}

    public record Request(
            @NotBlank @Size(max = 4000) String question,
            @NotBlank @Size(max = 2000) String answer
    ) {}

    public record Response(
            int score,
            String feedbackText,
            String model
    ) {}

    public record Error(
            String code,
            String message,
            String requestId
    ) {}
}
