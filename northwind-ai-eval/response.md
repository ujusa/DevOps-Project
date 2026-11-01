# NorthWind Script Generator: Findings & Recommendations

**To:** Project Manager, ExampleCorp
**Re:** Making the AI query tool reliable and measurable

## Summary
We found three causes of the unreliable output: the prompt was vague, the output format was undefined, and nothing checked or retried a failed script. We fixed the prompt and added an automated evaluation, so prompt quality is now a measured number and no longer a judgment call.

## What was going wrong
With the original one-line prompt, the model had to guess the API, the output format and the business rules. That causes predictable failures:

- **Rule violations:** falls back to `sqlite3` and raw SQL.
- **Hallucinated fields:** e.g. `customer['Name']` instead of `CompanyName` → runtime errors.
- **Wrong business logic:** revenue without discounts, inconsistent date ranges.
- **Inconsistent formats:** `20`, `"20"`, `["Beverages"]` vs `"Beverages"`, printed text vs data. Answers can't be compared or used downstream.
- **Non-reproducible:** default sampling temperature gives different scripts for the same question.

## What we changed (`ScriptGenerator.java`)
1. **A precise prompt.** The exact `nw_api` function list is generated from the module itself, so it can never drift. It adds hard rules (no SQL, allowed imports only), a fixed output contract (answer in `result`, typed by question kind) and business definitions (revenue formula, what "in 1997" means). It ends with worked examples.
2. **Deterministic decoding:** temperature 0 and a fixed seed.
3. **Guardrails before execution:** code is extracted from markdown, and SQL, banned imports and a missing `result` are rejected.
4. **Self-repair:** if a script fails a check or errors at runtime, the error is sent back to the model for up to 3 attempts.
5. **Safe execution:** the Python runner enforces an import allow-list, blocks SQL and file/exec access, and runs under a timeout.

## How we measure quality (`Evaluate.java`)
- Each question in `ground_truth.json` has a reference script. Its live result is the expected answer, so the test set stays correct if the data changes.
- Answers are compared in layers: **exact**, then **tolerant**. Tolerant means number tolerance, case and whitespace, list order, numeric strings, and a single-item list vs a scalar. Formatting noise doesn't count as a failure, but genuinely wrong answers still fail.
- Every failure is labelled **wrong answer**, **runtime error**, **rule violation** or **missing**, so the report shows *why* the tool fails, not just how often.
- Optional repeated runs (`--runs N`) produce a **reproducibility** score.
- Output: a console summary plus `evaluation_report.json` for tracking over time.

## How to use it
```bash
bash generate_outputs.sh                    # improved prompt → user_outputs.json
bash generate_outputs.sh base.json --baseline   # original prompt, for comparison
bash evaluate.sh                            # score user_outputs.json
bash evaluate.sh base.json                  # score the baseline
gradle test                                 # unit tests
```
Compare the two `evaluate.sh` reports to quantify the improvement on your own data and model.

## Recommended next steps
1. **Grow the test set** from real analyst questions (aim for 50–100), tagged by type (count, ranking, revenue, time-based).
2. **Run the evaluation in CI** on every prompt or model change, and block regressions below an agreed accuracy threshold.
3. **Show the generated script** next to each answer so analysts can verify it, and flag answers that needed repairs as lower confidence.
4. **Track cost and latency** per question alongside accuracy when comparing models.
5. **Gate the rollout**: release to a pilot group once accuracy and reproducibility meet the agreed bar.
