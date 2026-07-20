package com.org.llm.eval.dto;

import java.util.List;

public record AskRequest(String system, String question, List<String> expectedKeywords) {
}
