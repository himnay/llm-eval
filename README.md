# llm-eval — Golden-Dataset Evaluation Harness

Compares the four retrieval approaches in this workspace on the **same questions**:

| System | Repo | Endpoint |
|---|---|---|
| Vector RAG (OpenSearch kNN) | `llm-rag/llm-rag-pipeline` | `POST /api/v1/generate` |
| Vectorless RAG (BM25/PageIndex) | `llm-rag/llm-rag-vectorless` | `POST /api/rag/chat` |
| Graph RAG (Neo4j) | `llm-rag/llm-rag-graph` | `POST /api/v1/rag/query` |
| OKF (LLM index navigation) | `llm-OKF/okf-chat` | `POST /api/v1/okf/chat` |

## How it works

1. `src/main/resources/golden-dataset.json` holds questions plus `expectedKeywords` — the facts a
   correct answer must mention.
2. `EvalRunner` posts every question to every configured system (generic: request/response JSON
   field names live in `application.yaml`, so adding a system needs no code).
3. Scoring is **keyword recall** (fraction of expected keywords present, case-insensitive) —
   deterministic and CI-friendly. An LLM-as-judge scorer is the natural upgrade when finer
   resolution is needed.
4. A markdown report (`eval-report.md`) is written with mean accuracy, p50/p95 latency, mean
   answer length and error counts per system, plus a per-question accuracy matrix.

## Running

```bash
# start whichever target systems you want to compare, then:
mvn spring-boot:run

# override system URLs / report location:
RAG_PIPELINE_URL=http://localhost:8081 EVAL_REPORT_PATH=/tmp/report.md mvn spring-boot:run
```

Systems that are down score 0 and are marked `unavailable` — the run never aborts.

## Extending the dataset

Add entries to `golden-dataset.json`:

```json
{
  "id": "q6-my-topic",
  "question": "…",
  "expectedKeywords": ["fact-a", "fact-b"]
}
```

Keep keywords short, factual and unambiguous — they are substring-matched, not semantically
matched. The dataset shipped here is a starter; replace it with questions grounded in the corpus
you actually ingest into the four systems so the comparison is meaningful.
