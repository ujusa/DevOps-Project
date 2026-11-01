# NorthWind AI Script Generation: Prompting + Evaluation

A working solution to the "improve the prompt / build an evaluation" exercise.
An LLM (OpenAI API) turns natural-language questions into Python scripts that use the `nw_api`
module (never raw SQL). Java runs them and scores them against reference answers.

> The original exercise files weren't available, so `nw_api.py`, `runner.py`, `ScriptRunner.java`,
> `northwind.db` (a small deterministic sample built by `python/build_db.py`) and `ground_truth.json`
> are reconstructions. Swap in the real ones; the deliverables only depend on the interfaces described below.

## Deliverables
| File | Purpose |
|---|---|
| `src/main/java/ScriptGenerator.java` | Strict prompt (API surface generated live), temp-0 decoding, static checks, self-repair loop, OpenAI client, CLI that writes `user_outputs.json` |
| `src/main/java/Evaluate.java` | Runs the reference scripts, compares answers in layers (exact/tolerant), classifies failures, reproducibility score, report |
| `response.md` | Customer-facing findings and next steps |

## Supporting files
- `python/nw_api.py`: read-only data API over `northwind.db`
- `python/runner.py`: safe executor (AST allow-list, SQL block). Contract: script assigns `result`. `--describe` prints the API.
- `src/main/java/ScriptRunner.java`: subprocess wrapper with timeout
- `src/test/resources/ground_truth.json`: 15 questions + reference scripts (`python/make_ground_truth.py`)
- `examples/sample_user_outputs.json`: hand-made outputs with typical failure modes, used to demo the evaluator

## Run
```bash
python3 python/build_db.py                 # once (the scripts also do this automatically)
gradle test                                # 19 unit tests, no API key needed (fake LLM)
export OPENAI_API_KEY=...                  # optional: OPENAI_MODEL (default gpt-4o-mini), OPENAI_BASE_URL
bash generate_outputs.sh [user_outputs.json] [ground_truth.json] [--runs N] [--baseline]
bash evaluate.sh [user_outputs.json] [ground_truth.json]
bash evaluate.sh examples/sample_user_outputs.json   # demo without an API key
```
