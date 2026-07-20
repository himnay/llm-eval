package com.org.llm.eval.dto;

public record JudgeResult(String judge, Double score, String verdict, String reasoning, long latencyMs, String error) {
}
