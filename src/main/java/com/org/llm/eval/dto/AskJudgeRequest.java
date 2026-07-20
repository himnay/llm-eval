package com.org.llm.eval.dto;

import java.util.List;

public record AskJudgeRequest(String system, String question, List<String> expectedKeywords,
                               String referenceAnswer, String judge) {
}
