package com.portfolio.invest.application.allocation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SubmitAssessmentCommand(
        @NotNull @Size(min = 1) @Valid List<AnswerInput> answers) {}
