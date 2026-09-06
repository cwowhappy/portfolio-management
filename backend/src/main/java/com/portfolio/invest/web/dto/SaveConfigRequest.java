package com.portfolio.invest.web.dto;

import java.util.List;

public record SaveConfigRequest(Boolean enabled, List<String> disabledTools) {}
