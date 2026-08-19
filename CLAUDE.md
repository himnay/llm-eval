20# llm-eval

REST API for evaluating local Ollama models against a golden question dataset. No batch runner —
Claude Code drives it question-by-question and verifies answers itself.

## <span style="color:hsl(334,68%,44%)">Running an evaluation</span>

1. The user starts the app themselves (from IntelliJ, so they can see live logs) — Claude Code
   must never run `mvn spring-boot:run` or otherwise start it. Before doing anything else, check
   it's up with `GET /api/systems`; if that fails, ask the user to start it and wait, don't start
   it yourself.
2. List configured models: `GET /api/systems`.
3. Read each category file in `golden-dataset/*.json` at the project root (each file = one
   category, each entry has `id`, `question`, `expectedKeywords`).
4. For the current model, go category by category, in file order. Within a category, ask
   questions in array order.
5. For each question, call:
   ```
   POST /api/ask
   Content-Type: application/json

   {"system": "<model-name>", "question": "<question text>", "expectedKeywords": ["..."]}
   ```
   Response: `{"system", "answer", "accuracy", "latencyMs", "error"}`. `accuracy` is keyword
   recall (fraction of `expectedKeywords` present, case-insensitive substring match) — a rough
   signal, not the verdict. Read `answer` yourself and judge correctness/completeness.
6. Once a model has been asked every question in every category, write/append that model's
   results to `eval-report.md` at the project root before moving on — don't wait until every
   model is done. Never overwrite or delete prior content in this file — always append.
   - At the start of a new run (i.e. this is the first model being written in this session),
     append a run header first: `## Run: <ISO-8601 timestamp>`.
   - Under that run's header, add one subsection per model as you finish it, e.g.
     `### qwen3:4b`.
   - Per model, include: per-category accuracy (mean `accuracy` from `/api/ask`, plus your own
     correctness judgment since keyword recall alone is a rough signal), overall mean accuracy
     and mean latency, and any questions that errored (`error` field set) or that you judged
     wrong despite high keyword recall (hallucinated/off-topic answers score well on substring
     match — call these out).
   If `eval-report.md` doesn't exist yet, create it with a top-level heading, then the run header
   and model sections as above.
7. Unload the model before moving to the next one:
   ```
   POST /api/unload/{system}
   ```
   This evicts the model from Ollama's VRAM/RAM (`keep_alive: 0`) so models don't pile up
   resident at once and degrade the machine. Do this every time before switching models, not just
   at the end of a run.
8. Move to the next model from `GET /api/systems` and repeat from step 4.

## <span style="color:hsl(154,68%,36%)">Notes</span>

- Local models run on Ollama (`http://localhost:11434`) — check `curl localhost:11434/api/tags`
  and `ps aux | grep llama-server` if a model seems unreachable or stuck.
- `qwen3:4b` (and other thinking-capable models) run a hidden reasoning pass before answering;
  `stream: false` means the request blocks until that finishes. Expect answers to take tens of
  seconds on CPU-only inference — not a bug.
- `POST /api/ask` timeout is `request-timeout-seconds` in `application.yaml` (default 120s via
  `EVAL_REQUEST_TIMEOUT_SECONDS`). A timeout comes back as `{"error": "...Request cancelled"}`,
  not an HTTP error — check that field.app
- No dataset loading or report generation happens inside the Java app anymore — both were removed
  when the app moved from a batch `CommandLineRunner` to this REST API.
- Adding a model already pulled via `ollama pull` is config-only (uncomment/add its block in
  `application.yaml`). A raw local `.gguf` (not yet an Ollama model) needs one extra step first:
  write a one-line `Modelfile` (`FROM /path/to/model.gguf`), then
  `ollama create <name>:<tag> -f Modelfile` to register it — after that it's just another
  `eval.systems` entry pointing at that name.
