# <span style="color:hsl(333,68%,44%)">LLM Retrieval Systems — Evaluation Report</span>

Generated: 2026-07-19T05:50:09.402735240Z  
Questions: 5

| System                 | Mean accuracy | p50 latency (ms) | p95 latency (ms) | Mean answer chars | Errors |
|------------------------|---------------|------------------|------------------|-------------------|--------|
| rag-pipeline (vector)  | 0.00          | 16               | 208              | 0                 | 5      |
| rag-vectorless (BM25)  | 0.00          | 6                | 7                | 0                 | 5      |
| rag-graph (Neo4j)      | 0.00          | 2                | 6                | 0                 | 5      |
| okf (index navigation) | 0.00          | 4                | 6                | 0                 | 5      |

## <span style="color:hsl(153,68%,36%)">Per-question accuracy</span>

| Question        | rag-pipeline (vector) | rag-vectorless (BM25) | rag-graph (Neo4j) | okf (index navigation) |
|-----------------|-----------------------|-----------------------|-------------------|------------------------|
| q1-departments  | unavailable           | unavailable           | unavailable       | unavailable            |
| q2-tech-stack   | unavailable           | unavailable           | unavailable       | unavailable            |
| q3-database     | unavailable           | unavailable           | unavailable       | unavailable            |
| q4-auth         | unavailable           | unavailable           | unavailable       | unavailable            |
| q5-rag-approach | unavailable           | unavailable           | unavailable       | unavailable            |
