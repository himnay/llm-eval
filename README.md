# <span style="color:hsl(168,80%,58%)">llm-eval — Local LLM Evaluation Harness</span>

<img src="image/spring-logo.png" alt="logo" width="80"/>

`llm-eval` is a small Spring Boot REST API with one job: given a system name and a question, ask
the matching local model (Ollama) and hand back its answer plus a keyword-recall score. There's no
internal batch loop or LLM-as-judge call anymore — the golden dataset lives on disk at
`golden-dataset/`, and something external (typically Claude Code, following `CLAUDE.md`) reads it,
drives `/api/ask` question-by-question in order, does the actual correctness verification, and
writes `eval-report.md` incrementally as it goes.

The golden dataset isn't generic trivia. It's mined from this author's own `learning-*.md` study
notes (Java, Spring, Kafka, Kubernetes, databases, security, system design, ...), so "does this
model actually know this material" is a real, checkable question rather than a proxy for one.

---

## <span style="color:hsl(306,80%,58%)">Table of contents</span>

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

## <span style="color:hsl(83,80%,58%)">1. 🎯 Why evaluate local models this way</span>

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

## <span style="color:hsl(221,80%,58%)">2. 🔹 Scoring: keyword recall + external verification</span>

`POST /api/ask` scores every answer one deterministic way — **keyword recall** (`AnswerScorer`):
the fraction of `expectedKeywords` found as case-insensitive substrings of the answer. Free,
instant, and returned inline in the response. Its ceiling is real: it cannot detect fluent nonsense
that happens to mention the right nouns (a hallucinated answer can still score high).

That ceiling is deliberately not patched inside the app with another API call. Instead, whoever
drives the dataset through `/api/ask` — normally Claude Code, per `CLAUDE.md` — reads each answer
and judges correctness itself, using `accuracy` as a rough first signal rather than the verdict.
There used to be an in-process Anthropic-backed `JudgeScorer` doing this per-question; it's gone —
the external caller *is* the judge now, which also means no `ANTHROPIC_API_KEY` is needed to run
an eval at all.

---

<a id="architecture-how-evalrunner-orchestrates-a-run"></a>

## <span style="color:hsl(358,80%,58%)">3. 🔀 Architecture: a stateless REST surface, no batch loop</span>

The app boots as a normal Spring MVC web service (`LlmEvalApplication`, default
`WebApplicationType`) and stays up on `http://localhost:8080` — it does nothing on its own until
asked. Two classes do all the work:

- **`EvalController`** (`src/main/java/com/org/llm/eval/EvalController.java`) — the REST surface:
  - `GET /api/systems` — configured model names.
  - `POST /api/ask` — `{system, question, expectedKeywords}` → `{system, answer, accuracy,
    latencyMs, error}`.
  - `POST /api/unload/{system}` — evicts that model from Ollama.
- **`EvalRunner`** (`@Service`, not `CommandLineRunner`) — does the actual work per call: builds
  the request body from `extraRequestFields` + `{questionField: question}`, POSTs to the system's
  `url`, extracts the answer at `answerField`, and (if `expectedKeywords` was supplied) scores it
  with `AnswerScorer`.

There's no dataset loading, no cross-product loop, and no report rendering inside the app —
`/api/ask` handles exactly one (system, question) pair per call and returns immediately. Iterating
every category/question/model combination, and writing `eval-report.md`, is the caller's job (see
[§7](#running-it) and `CLAUDE.md`).

### <span style="color:hsl(136,80%,58%)">The generic REST adapter — same shape for every local model</span>

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

### <span style="color:hsl(273,80%,58%)">Per-call result shape</span>

```java
public record AskResult(String system, String answer, Double accuracy, long latencyMs, String error) {}
```

`accuracy` is `null` when the caller doesn't pass `expectedKeywords` (skips scoring entirely). A
failed call (unreachable model, timeout, malformed response) never throws out of the controller —
it comes back as a normal `200` response with `answer: null` and `error` populated, so a caller
looping over many questions can just check `error` per response instead of catching exceptions.

---

<a id="configuration-model--evalproperties"></a>

## <span style="color:hsl(51,80%,50%)">4. 🤖 Configuration model — `EvalProperties`</span>

Bound from `application.yaml` under `eval.*` via `@ConfigurationProperties` +
`@ConfigurationPropertiesScan` — no explicit `@Bean` wiring.

| Property | Meaning | Default |
|---|---|---|
| `eval.request-timeout-seconds` | Per-call HTTP read timeout (local models can be slow to first-token) | `${EVAL_REQUEST_TIMEOUT_SECONDS:120}` |
| `eval.systems[].name` | Name passed as `system` in `/api/ask` requests | — |
| `eval.systems[].url` | Full endpoint URL | — |
| `eval.systems[].question-field` / `answer-field` | Request/response JSON field names | — |
| `eval.systems[].extra-request-fields` | Fixed fields merged into every request body (e.g. Ollama's `model`, `stream`) | none |
| `eval.systems[].headers` | Optional static headers | none |

---

<a id="the-golden-dataset"></a>

## <span style="color:hsl(188,80%,58%)">5. 🔹 The golden dataset</span>

`golden-dataset/` at the project root (not on the classpath — the app never reads it) holds one
JSON file per topic instead of one monolithic file, so each topic can be curated, reviewed, and
regenerated independently. Every file is a flat array of:

```json
{"id": "db-001", "question": "...", "expectedKeywords": ["...", "..."]}
```

Whoever drives an eval reads these files directly off disk and passes `question` +
`expectedKeywords` straight through to `/api/ask` — there's no Java-side dataset model anymore.

Topics are mined directly from this author's `learning-*.md` notes (a separate `learning` repo) —
one or a few source files per JSON output, `id` prefixed per topic (`db-001`, `k8s-001`,
`spb-001`, ...) so files can grow independently without ID collisions. **1,562 questions across 15
topic files** as of this writing:

| File | Questions | Source notes | Topic |
|---|---|---|---|
| `ai-springai.json` | 90 | `learning-ai-*` | Claude/Claude Code, AI concepts, Spring AI (chat/MCP/RAG) |
| `architecture.json` | 75 | `learning-architecture-*` | Clean code, DSA, design patterns, microservices, principles |
| `cloud-devops.json` | 81 | `learning-cloud-*`, `learning-devops.md` | AWS, Docker, Terraform, PCF, DevOps |
| `db.json` | 95 | `learning-db-*` | Cassandra, DynamoDB, Elasticsearch, MongoDB, MyBatis, Neo4j, Oracle, Postgres, Redis, vector DBs |
| `java-core.json` | 134 | `learning-java-26/concurrency/core/faq.md` | Language fundamentals, concurrency primitives, JLS rules |
| `java-jvm.json` | 132 | `learning-java-gc/jvm/optimization/stream-cheatsheet/threads/virtualthreads.md` | GC algorithms, JVM internals, Stream API, threading |
| `kubernetes.json` | 104 | `learning-k8s-*` | kubectl, resource kinds, EKS/OpenShift/minikube |
| `messaging.json` | 105 | `learning-messaging-*` | Kafka (+ Connect/Streams/Schema Registry), RabbitMQ, Debezium |
| `observability-scm.json` | 90 | `learning-observability-*`, `learning-scm-*` | Dynatrace, logging, Prometheus, Splunk, Git, Gradle, Maven |
| `security-iam.json` | 95 | `learning-IAM-*`, `learning-security-*` | Active Directory, OpenIDM, OAuth2/OIDC, Spring Security, TLS |
| `spring-boot-core.json` | 164 | `learning-spring-annotation/boot-core/boot-data/boot-web/cache/patterns.md` | Annotations, bean lifecycle, auto-configuration, data/web layers |
| `spring-ecosystem.json` | 100 | `learning-spring-batch/camunda/cloud-*/tracing/drools/feign/graphql/grpc/modulith/state-machine/vault.md` | The rest of the Spring module surface |
| `spring-reactive-test.json` | 132 | `learning-spring-reactive/test/resilience4j/migration.md` | Reactor/WebFlux, Spring test utilities, Resilience4j, version migration |
| `system-design.json` | 85 | `learning-design-*` | Bloom filters, consistent hashing, CRDTs, sagas, URL shorteners, ... |
| `testing.json` | 80 | `learning-test-*` | BDD, contract testing, DR, mutation testing, perf testing, Testcontainers, WireMock |

Because `expectedKeywords` drives *keyword-recall* substring matching, every keyword is a literal
term pulled from the source note (a config key, annotation name, CLI flag, specific number) — not
a paraphrase — so an accidental substring match (e.g. a bare `"1"` matching inside `"18"`) is
avoided by picking specific-enough keywords per [Extending the dataset](#extending-the-dataset).

---

<a id="build-super-pom-and-the-bom"></a>

## <span style="color:hsl(326,80%,58%)">6. 🏗️ Build: super-pom and the BOM</span>

`llm-eval` inherits `com.org.llm:super-pom` (this workspace's corporate parent — Spring Boot
parent, enforcer rules, Jacoco/Spotless/PITest plugin management, the `security-scan` and
`mutation-test` profiles), which in turn imports `com.org.learning:learning-bom` — the single
source of truth for every managed dependency version. Adding or bumping a dependency version
happens in the BOM, not here.

```xml
<parent>
    <groupId>com.org.llm</groupId>
    <artifactId>super-pom</artifactId>
    <version>1.0.0</version>
</parent>
```

---

<a id="running-it"></a>

## <span style="color:hsl(103,80%,58%)">7. 🚀 Running it</span>

**1. Make sure the local models you want to test are pulled and Ollama is running:**

```bash
ollama list
ollama serve   # if not already running as a service
```

**2. Start the app.** Do this yourself (IntelliJ run configuration, so you get live logs, or
`mvn spring-boot:run` from a terminal) — if you're asking Claude Code to run an eval, it will
*not* start the app itself; it checks `GET /api/systems` and waits for you to start it (see
`CLAUDE.md`). No `ANTHROPIC_API_KEY` or any credential is needed — nothing in this app calls out
to Anthropic anymore.

```bash
mvn spring-boot:run
# override the per-call timeout if needed:
EVAL_REQUEST_TIMEOUT_SECONDS=180 mvn spring-boot:run
```

**3. Drive an eval.** The easiest way is to just ask Claude Code — it reads `CLAUDE.md`, iterates
`golden-dataset/*.json` category by category, calls `/api/ask` per question, verifies each answer
itself, and appends results to `eval-report.md`. To do it by hand instead:

```bash
# what models are configured?
curl http://localhost:8080/api/systems

# ask one question
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"system":"qwen3:4b","question":"What is dependency injection?","expectedKeywords":["ioc","container"]}'

# done with a model? free it from Ollama's VRAM/RAM before moving to the next one
curl -X POST http://localhost:8080/api/unload/qwen3:4b
```

A model that's unreachable, too slow, or errors comes back as a normal `200` with `error`
populated rather than throwing — a caller looping over many questions doesn't need to handle
exceptions, just check that field per response.

```bash
mvn test   # AnswerScorer unit tests, no network calls
```

---

<a id="adding-or-swapping-a-model"></a>

## <span style="color:hsl(241,80%,58%)">8. 🔹 Adding or swapping a model</span>

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

## <span style="color:hsl(18,80%,58%)">9. 🔹 Extending the dataset</span>

Add a new file under `golden-dataset/` at the project root (any filename) or append to an existing
one:

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

## <span style="color:hsl(156,80%,58%)">10. 🛡️ Failure handling and resilience</span>

- **Per-call try/catch, not per-run.** A model's connection refusal, timeout, or malformed
  response degrades to a normal `200` response with `answer: null` and `error` populated — it
  never throws out of `/api/ask`, so a caller looping over many questions never has to catch
  exceptions mid-run.
- **Bounded timeouts on every call** — a 5s connect timeout and configurable (default 120s,
  generous for a cold local model still loading into VRAM) read timeout via
  `JdkClientHttpRequestFactory`.
- **Defensive JSON field extraction** — a missing `answer-field` degrades to `""` (scored `0`),
  never a `NullPointerException`.

---

<a id="known-limitations"></a>

## <span style="color:hsl(293,80%,58%)">11. 🔹 Known limitations</span>

- **Sequential by construction** — `/api/ask` handles one question at a time; total wall-clock
  time for a full run scales with `models × questions × per-call latency`, dominated by
  local-model inference (CPU-only inference, or a thinking-capable model's hidden reasoning pass,
  can easily push a single answer past a minute).
- **Keyword recall has a hard ceiling on nuance** — it cannot detect fluent nonsense that happens
  to mention the right nouns. There's no in-process judge anymore to cover this; it relies on
  whoever calls `/api/ask` (typically Claude Code) reading the answer and judging it.
- **Coverage tracks the source notes, not a fixed spec** — if a `learning-*.md` topic is added or
  rewritten later, its `golden-dataset/*.json` counterpart needs a corresponding refresh to stay
  grounded in current content.

---

<a id="project-layout"></a>

## <span style="color:hsl(71,80%,58%)">12. 🏗️ Project layout</span>

```
src/main/java/com/org/llm/eval/
  LlmEvalApplication.java   Boot entry point; @ConfigurationPropertiesScan
  EvalController.java       REST surface: GET /api/systems, POST /api/ask, POST /api/unload/{system}
  EvalRunner.java           @Service — one call to one system's model endpoint, scores if asked
  EvalProperties.java       @ConfigurationProperties(prefix = "eval") — systems + request timeout
  AnswerScorer.java         Pure static keyword-recall scorer

src/main/resources/
  application.yaml          Local-model list (Ollama) + request timeout
  banner.txt                Spring Boot startup banner

src/test/java/com/org/llm/eval/
  AnswerScorerTest.java     Pins down every keyword-recall scoring rule

golden-dataset/              One versioned *.json file per topic — the rubric, mined from learning-*.md
CLAUDE.md                    Step-by-step eval-driving instructions for Claude Code
eval-report.md                Accumulated eval history — appended to, per run, never overwritten
```
