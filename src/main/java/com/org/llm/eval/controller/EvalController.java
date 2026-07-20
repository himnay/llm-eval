package com.org.llm.eval.controller;

import com.org.llm.eval.service.EvalRunner;
import com.org.llm.eval.dto.AskJudgeRequest;
import com.org.llm.eval.dto.AskJudgeResult;
import com.org.llm.eval.dto.AskRequest;
import com.org.llm.eval.dto.AskResult;
import com.org.llm.eval.dto.EvalProperties;
import com.org.llm.eval.dto.JudgeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Stateless per-question REST surface. The golden dataset lives in {@code golden-dataset/} at the
 * project root (not on the classpath) and is read + iterated by whoever drives this API — one
 * question at a time, in whatever order they choose — rather than by an internal batch loop.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class EvalController {

    private final EvalRunner runner;

    @GetMapping("/api/systems")
    public List<String> systems() {
        return runner.systems().stream().map(EvalProperties.SystemUnderTest::name).toList();
    }

    @PostMapping("/api/ask")
    public AskResult ask(@RequestBody AskRequest req) {
        EvalProperties.SystemUnderTest system = runner.findSystem(req.system());
        return runner.ask(system, req.question(), req.expectedKeywords());
    }

    /**
     * Like {@code /api/ask}, but also grades the answer with an LLM-as-judge model — a richer
     * alternative to (not a replacement for) the deterministic keyword-recall {@code accuracy}
     * already returned in {@code ask}. Judge model defaults to {@code eval.judge-system}; pass
     * {@code judge} in the request body to use a different configured judge (see
     * {@code eval.judges}).
     */
    @PostMapping("/api/ask-judge")
    public AskJudgeResult askJudge(@RequestBody AskJudgeRequest req) {
        EvalProperties.SystemUnderTest system = runner.findSystem(req.system());
        AskResult ask = runner.ask(system, req.question(), req.expectedKeywords());

        String judgeName = req.judge() != null ? req.judge() : runner.defaultJudge();
        if (judgeName == null) {
            throw new IllegalStateException("no judge configured: set eval.judge-system or pass \"judge\" in the request");
        }
        EvalProperties.SystemUnderTest judgeSystem = runner.findJudge(judgeName);
        JudgeResult judge = runner.judge(judgeSystem, req.question(), ask.answer(), req.referenceAnswer(), req.expectedKeywords());
        return new AskJudgeResult(ask, judge);
    }

    @PostMapping("/api/unload/{system}")
    public boolean unload(@org.springframework.web.bind.annotation.PathVariable String system) {
        return runner.unload(runner.findSystem(system));
    }
}
