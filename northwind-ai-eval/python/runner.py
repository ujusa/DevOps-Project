"""Executes a generated script safely and prints a single JSON line.

Usage:
  python runner.py <script.py>    -> {"ok": true, "result": ...} | {"ok": false, "error": "..."}
  python runner.py --describe     -> prints the nw_api surface (used to build the prompt)

Contract: the script must assign its final answer to a variable named `result`.
Safety: AST allow-list of imports, no SQL / sqlite3 / file / exec access. The
Java side additionally enforces a wall-clock timeout on this process.
"""
import ast
import inspect
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nw_api  # noqa: E402

ALLOWED_IMPORTS = {"nw_api", "json", "math", "statistics", "collections", "datetime",
                   "itertools", "functools", "operator", "re", "decimal"}
BANNED_NAMES = {"open", "exec", "eval", "compile", "__import__", "globals", "locals",
                "vars", "getattr", "setattr", "delattr", "input", "breakpoint"}
SQL_PATTERN = re.compile(r"\b(SELECT\s+.+\s+FROM|INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM)\b",
                         re.IGNORECASE | re.DOTALL)


class Violation(Exception):
    pass


def check(source):
    tree = ast.parse(source)
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            mods = [a.name for a in node.names] if isinstance(node, ast.Import) else [node.module or ""]
            for m in mods:
                if m.split(".")[0] not in ALLOWED_IMPORTS:
                    raise Violation(f"import of '{m}' is not allowed")
        elif isinstance(node, ast.Name) and node.id in BANNED_NAMES:
            raise Violation(f"use of '{node.id}' is not allowed")
        elif isinstance(node, ast.Attribute) and (node.attr.startswith("_")):
            raise Violation(f"access to private attribute '{node.attr}' is not allowed")
        elif isinstance(node, ast.Constant) and isinstance(node.value, str) and SQL_PATTERN.search(node.value):
            raise Violation("raw SQL is not allowed; use nw_api functions")
    return tree


def to_jsonable(v):
    if isinstance(v, (set, frozenset, tuple)):
        return [to_jsonable(x) for x in v]
    if isinstance(v, list):
        return [to_jsonable(x) for x in v]
    if isinstance(v, dict):
        return {str(k): to_jsonable(x) for k, x in v.items()}
    if hasattr(v, "isoformat"):
        return v.isoformat()
    if type(v).__name__ == "Decimal":
        return float(v)
    return v


def run(source):
    tree = check(source)
    ns = {"__name__": "__generated__", "nw_api": nw_api}
    exec(compile(tree, "<generated>", "exec"), ns)  # checked above
    if "result" not in ns:
        raise Violation("script did not assign a variable named 'result'")
    return to_jsonable(ns["result"])


def describe():
    lines = []
    for name, fn in inspect.getmembers(nw_api, inspect.isfunction):
        if name.startswith("_"):
            continue
        lines.append(f"nw_api.{name}{inspect.signature(fn)}")
        lines.append("    " + " ".join(inspect.getdoc(fn).split()))
    return "\n".join(lines)


def main(argv):
    if len(argv) == 2 and argv[1] == "--describe":
        print(describe())
        return 0
    if len(argv) != 2:
        print(json.dumps({"ok": False, "error": "usage: runner.py <script.py>"}))
        return 2
    with open(argv[1], encoding="utf-8") as f:
        source = f.read()
    try:
        print(json.dumps({"ok": True, "result": run(source)}, default=str))
    except Exception as e:  # report every failure as data, never crash the caller
        print(json.dumps({"ok": False, "error": f"{type(e).__name__}: {e}"}))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
