package com.portfolio.invest.application.allocation;

import java.util.List;

public record QuestionView(String id, String dimension, String text, List<OptionView> options) {}
