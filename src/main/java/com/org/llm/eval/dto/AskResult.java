package com.org.llm.eval.dto;

public record AskResult(String system, String answer, Double accuracy, long latencyMs, String error) {
}
