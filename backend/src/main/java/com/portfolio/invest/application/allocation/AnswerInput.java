package com.portfolio.invest.application.allocation;

import jakarta.validation.constraints.NotBlank;

public record AnswerInput(@NotBlank String questionId, @NotBlank String optionId) {}
