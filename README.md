# llm-eval — Local LLM Evaluation Harness

<img src="image/spring-logo.png" alt="logo" width="80"/>

`llm-eval` is a Spring Boot CLI application with one job: ask the same set of questions to every
LLM you've downloaded and run locally (Ollama), score the answers two independent ways, and
produce a markdown report that says — objectively, repeatably, and without a human re-reading a
dozen chat transcripts — which local model is actually best at which kind of question.

The golden dataset isn't generic trivia. It's mined from this author's own `learning-*.md` study
notes (Java, Spring, Kafka, Kubernetes, databases, security, system design, ...), so "does this
model actually know this material" is a real, checkable question rather than a proxy for one.

---

## Table of contents

1. 🎯 [Why evaluate local models this way](#why-evaluate-local-models-this-way)
2. 🔹 [The two scoring strategies](#the-two-scoring-strategies)
3. 🔀 [Architecture: how `EvalRunner` orchestrates a run](#architecture-how-evalrunner-orchestrates-a-run)
4. 🤖 [Configuration model — `EvalProperties`](#configuration-model--evalproperties)
5. 🔹 [The golden dataset](#the-golden-dataset)
6. 🏗️ [Build: super-pom and the BOM](#build-super-pom-and-the-bom)
7. 🚀 [Running it](#running-it)
8. 🔹 [Adding or swapping a model](#adding-or-swapping-a-model)
9. 🔹 [Extending the dataset](#extending-the-dataset)
10. 🛡️ [Failure handling and resilience](#failure-handling-and-resilience)
11. 🔹 [Known limitations](#known-limitations)
12. 🏗️ [Project layout](#project-layout)

---

<a id="why-evaluate-local-models-this-way"></a>

## 1. 🎯 Why evaluate local models this way

Downloading a model from Hugging Face / Ollama and running it locally is easy. Knowing whether
it's actually *good* — good enough to trust for real work, good at code vs. good at recall, worth
the disk space vs. one of the other eleven models sitting in `ollama list` — is not. "It answered
my one test prompt fine" doesn't scale past the second model, and eyeballing chat transcripts
across nine locally-run models is not a repeatable process.

This harness answers that with the same discipline a CI regression suite applies to code: a fixed,
versioned question set (`golden-dataset/`), a fixed scoring rubric, one run that hits every model
under test, and one report that ranks them.

---

<a id="the-two-scoring-strategies"></a>

## 2. 🔹 The two scoring strategies

Every answer is scored two ways, deliberately kept independent so one can sanity-check the other:

| Scorer | How it works | Cost | Nuance |
|---|---|---|---|
| **Keyword recall** (`AnswerScorer`) | Fraction of `expectedKeywords` found as case-insensitive substrings of the answer | Free, instant, deterministic | Cannot detect fluent nonsense that happens to mention the right nouns |
| **LLM-as-judge** (`JudgeScorer`) | Sends the question, the expected facts, and the candidate answer to Claude (`claude-opus-4-8` by default) and asks for a 0.0–1.0 correctness/completeness score | One Anthropic API call per (model, question) pair | Tolerates paraphrase, penalizes confidently-wrong answers, gives partial credit |

Keyword recall is the floor — cheap enough to run on every question, every time. The judge is the
ceiling — it catches the cases keyword matching structurally can't (a correct answer phrased with a
synonym the keyword list didn't anticipate, or a wrong answer that happens to name the right term in
passing). Both scores land in the same report so a keyword-recall dip that isn't mirrored by a
judge-score dip is a useful signal in itself: the model was probably still right.

Set `eval.judge-enabled: false` to skip the judge and get a free, instant, CI-safe run.

---

<a id="architecture-how-evalrunner-orchestrates-a-run"></a>

## 3. 🔀 Architecture: how `EvalRunner` orchestrates a run

`EvalRunner` (`src/main/java/com/org/llm/eval/EvalRunner.java`) is a Spring `CommandLineRunner` —
the app boots with `WebApplicationType.NONE` (see `LlmEvalApplication`). It's a batch job, not a
service: run it, it runs to completion, it writes a file, it exits.

`run(...)` does five things, in order:

1. **Load the golden dataset** — every `*.json` file under `golden-dataset/` on the classpath is
   deserialized and concatenated into one `List<GoldenQuestion>` (see [§5](#the-golden-dataset)).
2. **Build the judge** (if `eval.judge-enabled`) — a single `AnthropicClient` via
   `AnthropicOkHttpClient.fromEnv()`, so credentials resolve the same way the `ant` CLI does
   (`ANTHROPIC_API_KEY`, then an `ant auth login` profile).
3. **Evaluate the full cross-product** — for every configured local model, for every golden
   question, POST to the model's endpoint and score the answer both ways.
4. **Render a markdown report** — a summary table (one row per model) plus a per-question keyword
   accuracy matrix.
5. **Write the report to disk** at `eval.report-path` (`eval-report.md` by default).

### The generic REST adapter — same shape for every local model

Every model under test is described generically by `EvalProperties.SystemUnderTest`:

```java
public record SystemUnderTest(
        String name,
        String url,
        String questionField,
        String answerField,
        Map<String, String> headers,
        Map<String, Object> extraRequestFields) {}
```

`evaluate(...)` builds a JSON request body from `extraRequestFields` (fixed per-model fields —
Ollama needs `model` and `stream: false`) plus `{questionField: question}`, POSTs it to `url`, and
reads the answer back out of the response at `answerField` via Jackson's `JsonNode` path
navigation. Every Ollama model hits the same `/api/generate` endpoint — only `extra-request-fields.model`
differs — so **swapping or adding a locally-downloaded model is a YAML edit, never a Java change**.

### Per-question result shape

```java
record Result(String system, String questionId, double accuracy, Double judgeScore,
              long latencyMs, int answerChars, String error) {}
```

`judgeScore` is `null` when the judge is disabled or a judge call itself failed (network/API
error) — that failure never drops the keyword-recall score for the same question, and never
aborts the run.

---

<a id="configuration-model--evalproperties"></a>

## 4. 🤖 Configuration model — `EvalProperties`

Bound from `application.yaml` under `eval.*` via `@ConfigurationProperties` +
`@ConfigurationPropertiesScan` — no explicit `@Bean` wiring.

| Property | Meaning | Default |
|---|---|---|
| `eval.dataset-path` | Classpath glob for golden-question files | `classpath:golden-dataset/*.json` |
| `eval.report-path` | Where the markdown report is written | `${EVAL_REPORT_PATH:eval-report.md}` |
| `eval.request-timeout-seconds` | Per-call HTTP read timeout (local models can be slow to first-token) | `${EVAL_REQUEST_TIMEOUT_SECONDS:120}` |
| `eval.judge-enabled` | Turn the LLM-as-judge scorer on/off | `${EVAL_JUDGE_ENABLED:true}` |
| `eval.judge-model` | Claude model used as judge | `${EVAL_JUDGE_MODEL:claude-opus-4-8}` |
| `eval.systems[].name` | Display name in report tables | — |
| `eval.systems[].url` | Full endpoint URL | — |
| `eval.systems[].question-field` / `answer-field` | Request/response JSON field names | — |
| `eval.systems[].extra-request-fields` | Fixed fields merged into every request body (e.g. Ollama's `model`, `stream`) | none |
| `eval.systems[].headers` | Optional static headers | none |

---

<a id="the-golden-dataset"></a>

## 5. 🔹 The golden dataset

`src/main/resources/golden-dataset/` holds one JSON file per topic instead of one monolithic file,
so each topic can be curated, reviewed, and regenerated independently. Every file is a flat array
of:

```java
public record GoldenQuestion(String id, String question, List<String> expectedKeywords) {}
```

Topics are mined directly from this author's `learning-*.md` notes (a separate `learning` repo) —
one or a few source files per JSON output, `id` prefixed per topic (`db-001`, `k8s-001`,
`spb-001`, ...) so files can grow independently without ID collisions:

| File | Source notes | Topic |
|---|---|---|
| `cloud-devops.json` | `learning-cloud-*`, `learning-devops.md` | AWS, Docker, Terraform, PCF, DevOps |
| `messaging.json` | `learning-messaging-*` | Kafka, Kafka Connect/Streams/Schema Registry, RabbitMQ, Debezium |
| *(more topics land here incrementally — see below)* | `learning-ai-*`, `learning-architecture-*`, `learning-db-*`, `learning-design-*`, `learning-IAM-*` / `learning-security-*`, `learning-java-*`, `learning-k8s-*`, `learning-observability-*` / `learning-scm-*`, `learning-spring-*`, `learning-test-*` | AI/Spring AI, architecture & DSA, databases, distributed-systems design, security/IAM, Java core/JVM, Kubernetes, observability/SCM, Spring (core, reactive/test, ecosystem), test engineering |

Because `expectedKeywords` drives *keyword-recall* substring matching, every keyword is a literal
term pulled from the source note (a config key, annotation name, CLI flag, specific number) — not
a paraphrase — so an accidental substring match (e.g. a bare `"1"` matching inside `"18"`) is
avoided by picking specific-enough keywords per [Extending the dataset](#extending-the-dataset).

---

<a id="build-super-pom-and-the-bom"></a>

## 6. 🏗️ Build: super-pom and the BOM

`llm-eval` inherits `com.org.llm:super-pom` (this workspace's corporate parent — Spring Boot
parent, enforcer rules, Jacoco/Spotless/PITest plugin management, the `security-scan` and
`mutation-test` profiles), which in turn imports `com.org.learning:learning-bom` — the single
source of truth for every managed dependency version, including `com.anthropic:anthropic-java`
(the SDK `JudgeScorer` uses). Adding or bumping a dependency version happens in the BOM, not here.

```xml
<parent>
    <groupId>com.org.llm</groupId>
    <artifactId>super-pom</artifactId>
    <version>1.0.0</version>
</parent>
```

---

<a id="running-it"></a>

## 7. 🚀 Running it

```bash
# 1. make sure the local models you want to compare are pulled and Ollama is running
ollama list
ollama serve   # if not already running as a service

# 2. resolve Anthropic credentials for the judge (skip if eval.judge-enabled: false)
ant auth login          # or: export ANTHROPIC_API_KEY=...

# 3. run the eval
./mvnw spring-boot:run

# override anything via environment variables:
OLLAMA_URL=http://localhost:11434 \
EVAL_JUDGE_ENABLED=true \
EVAL_JUDGE_MODEL=claude-opus-4-8 \
EVAL_REPORT_PATH=/tmp/report.md \
EVAL_REQUEST_TIMEOUT_SECONDS=120 \
./mvnw spring-boot:run
```

The app is a one-shot CLI run (`WebApplicationType.NONE`) — it loads the dataset, evaluates every
configured model, writes the report, logs where it landed, and exits. A model that's unreachable
or too slow scores 0 and is marked `unavailable`; the run itself never aborts because of it.

```bash
./mvnw test   # AnswerScorer + percentile() unit tests, no network calls
```

---

<a id="adding-or-swapping-a-model"></a>

## 8. 🔹 Adding or swapping a model

Every entry in `eval.systems` is config, not code. To add a newly-pulled model:

```yaml
eval:
  systems:
    - name: my-new-model:tag
      url: ${OLLAMA_URL:http://localhost:11434}/api/generate
      question-field: prompt
      answer-field: response
      extra-request-fields:
        model: my-new-model:tag
        stream: false
```

No Java change, no rebuild logic to touch — the next `spring-boot:run` includes it in the
cross-product and the comparison table.

---

<a id="extending-the-dataset"></a>

## 9. 🔹 Extending the dataset

Add a new file under `src/main/resources/golden-dataset/` (any filename — all `*.json` files are
loaded and merged) or append to an existing one:

```json
{
  "id": "myTopic-001",
  "question": "…",
  "expectedKeywords": ["fact-a", "fact-b"]
}
```

Guidelines, following directly from how `AnswerScorer` works (case-insensitive **substring**
match, not tokenized or semantic):

- **Ground every keyword in a real source note.** Don't encode a fact the corpus doesn't actually
  state — the point is testing recall of *your* notes, not general trivia.
- **Prefer 2–4 specific keywords per question.** A keyword like `"1"` will match inside `"18"`,
  `"100"`, any year — pick something long/specific enough that an accidental hit is implausible.
- **Prefix IDs per topic** (`db-`, `k8s-`, `spb-`, ...) so independently-grown files never collide.

---

<a id="failure-handling-and-resilience"></a>

## 10. 🛡️ Failure handling and resilience

- **Per-question try/catch, not per-run.** One model's connection refusal, timeout, or malformed
  response degrades to one `Result` row with `accuracy = 0` and a populated `error` — it can never
  abort the outer double loop.
- **Bounded timeouts on every call** — a 5s connect timeout and configurable (default 120s,
  generous for a cold local model still loading into VRAM) read timeout via
  `JdkClientHttpRequestFactory`.
- **Judge failures never take down keyword scoring.** A judge-call exception is caught
  independently per question; `judgeScore` is left `null` and the keyword-recall score for that
  question is still recorded.
- **Defensive JSON field extraction** — a missing `answer-field` degrades to `""` (scored `0`),
  never a `NullPointerException`.

---

<a id="known-limitations"></a>

## 11. 🔹 Known limitations

- **Sequential execution** — models and questions are evaluated in a nested loop with no
  concurrency; total wall-clock time scales with `models × questions × per-call latency`
  (dominated by local-model inference and, if enabled, judge round trips).
- **Keyword recall has a hard ceiling on nuance** — the judge score exists specifically to cover
  this, but the judge itself is an LLM call and isn't infallible either.
- **No historical trend tracking** — each run overwrites `eval-report.md`; commit it (or
  timestamp report filenames) to get a trend rather than a single snapshot.
- **Dataset extraction from `learning-*.md` is incremental** — topics land as separate files under
  `golden-dataset/` as they're mined; see [§5](#the-golden-dataset) for what's landed so far.

---

<a id="project-layout"></a>

## 12. 🏗️ Project layout

```
src/main/java/com/org/llm/eval/
  LlmEvalApplication.java   Boot entry point; WebApplicationType.NONE; @ConfigurationPropertiesScan
  EvalRunner.java           CommandLineRunner: orchestrates the eval loop, scores, renders + writes the report
  EvalProperties.java       @ConfigurationProperties(prefix = "eval") — systems, dataset glob, judge config
  GoldenQuestion.java       record(id, question, expectedKeywords) — one dataset entry
  AnswerScorer.java         Pure static keyword-recall scorer
  JudgeScorer.java          LLM-as-judge scorer (Anthropic API, claude-opus-4-8 by default)

src/main/resources/
  application.yaml          Local-model list (Ollama), dataset glob, judge config, report path
  golden-dataset/           One versioned *.json file per topic — the rubric, mined from learning-*.md
  banner.txt                Spring Boot startup banner

src/test/java/com/org/llm/eval/
  AnswerScorerTest.java     Pins down every keyword-recall scoring rule
  EvalRunnerTest.java       Pins down percentile() edge cases

eval-report.md              Most recent run's output (checked in as a worked example)
```
