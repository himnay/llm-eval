#!/usr/bin/env python3
"""Drives /api/ask sequentially over golden-dataset categories for one system.
Usage: run_eval.py <system> <out.jsonl> [max_categories] [max_questions_per_category]
"""
import json
import os
import sys
import time
import urllib.request

BASE = "http://localhost:8080"
DATASET_DIR = "/home/himansu/projects/llm-eval/golden-dataset"

system = sys.argv[1]
out_path = sys.argv[2]
max_categories = int(sys.argv[3]) if len(sys.argv) > 3 else None
max_questions = int(sys.argv[4]) if len(sys.argv) > 4 else None

files = sorted(f for f in os.listdir(DATASET_DIR) if f.endswith(".json"))
if max_categories:
    files = files[:max_categories]

with open(out_path, "a") as out:
    for fname in files:
        category = fname.removesuffix(".json")
        questions = json.load(open(os.path.join(DATASET_DIR, fname)))
        if max_questions:
            questions = questions[:max_questions]
        print(f"CATEGORY START {category} ({len(questions)} questions)", flush=True)
        for q in questions:
            payload = json.dumps({
                "system": system,
                "question": q["question"],
                "expectedKeywords": q.get("expectedKeywords", []),
            }).encode()
            req = urllib.request.Request(
                f"{BASE}/api/ask", data=payload,
                headers={"Content-Type": "application/json"}, method="POST")
            start = time.time()
            try:
                with urllib.request.urlopen(req, timeout=130) as resp:
                    result = json.loads(resp.read())
            except Exception as e:
                result = {"system": system, "answer": None, "accuracy": None,
                          "latencyMs": int((time.time() - start) * 1000), "error": str(e)}
            record = {"category": category, "id": q["id"], "question": q["question"],
                      "expectedKeywords": q.get("expectedKeywords", []), **result}
            out.write(json.dumps(record) + "\n")
            out.flush()
            print(f"  {q['id']} accuracy={result.get('accuracy')} "
                  f"latencyMs={result.get('latencyMs')} error={result.get('error')}", flush=True)
        print(f"CATEGORY DONE {category}", flush=True)

req = urllib.request.Request(f"{BASE}/api/unload/{system}", method="POST")
try:
    urllib.request.urlopen(req, timeout=15)
    print(f"MODEL UNLOADED {system}", flush=True)
except Exception as e:
    print(f"UNLOAD FAILED {e}", flush=True)

print("RUN COMPLETE", flush=True)
