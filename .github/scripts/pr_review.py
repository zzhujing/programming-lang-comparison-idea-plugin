#!/usr/bin/env python3
"""Call an OpenAI-compatible chat/completions endpoint to review a PR diff.

Usage: pr_review.py <pr.diff> <review.md>
Config via env: LLM_BASE_URL, LLM_MODEL, LLM_API_KEY, PR_TITLE, PR_BODY.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request

MAX_DIFF_CHARS = 48_000

IGNORED_BASENAMES = {"package-lock.json", ".DS_Store"}
IGNORED_DIRS = {"out", "build"}
IGNORED_SUFFIXES = (".vsix", ".zip", ".gif", ".png", ".jpg", ".jpeg", ".svg")

SYSTEM_PROMPT = """You are a rigorous but pragmatic senior code reviewer.
This repository is a monorepo: a JetBrains IDE plugin in Kotlin under `jetbrains/`
and a VS Code extension in TypeScript under `vscode/`, both calling an
OpenAI-compatible LLM chat/completions API.

Review the unified diff and reply in Markdown with exactly these sections:

1. **Summary** — 1-2 sentences on what the PR does.
2. **Findings** — bullets, each prefixed with `[blocker]`, `[should-fix]`, or `[nit]`,
   referencing file/line. Focus on correctness, security (e.g. API key handling),
   IntelliJ Platform / VSCode API misuse, concurrency, and performance.
   Do not restate what the diff obviously does. If there are no findings, say so.
3. **Verdict** — "✅ Approve" or "⚠️ Request changes".

Write in the same language as the PR title/description; if unclear, use English.
Be concise."""

DIFF_HEADER_RE = re.compile(r"^diff --git a/(.+?) b/(.+)$")


def load_diff(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as f:
        raw = f.read()

    kept, current, skipped_any = [], [], False

    def flush():
        nonlocal skipped_any
        if not current:
            return
        header = next((DIFF_HEADER_RE.match(l) for l in current if l.startswith("diff --git ")), None)
        if header is None:
            kept.append("".join(current))
            return
        old, new = header.group(1), header.group(2)
        name = "/".join(p for p in (old, new) if p != "/dev/null")
        segments = name.split("/")
        basename = segments[-1]
        ignored = (
            basename in IGNORED_BASENAMES
            or basename.endswith(IGNORED_SUFFIXES)
            or any(seg in IGNORED_DIRS for seg in segments[:-1])
        )
        if ignored:
            skipped_any = True
        else:
            kept.append("".join(current))
        current.clear()

    for line in raw.splitlines(keepends=True):
        if line.startswith("diff --git "):
            flush()
        current.append(line)
    flush()

    diff = "".join(kept)
    if len(diff) > MAX_DIFF_CHARS:
        diff = diff[:MAX_DIFF_CHARS] + "\n\n[... diff truncated ...]"
        skipped_any = True
    if skipped_any:
        diff = (
            "(Note: lock files, build outputs and binary assets are omitted; "
            "the diff may also be truncated due to length.)\n\n" + diff
        )
    return diff


def main() -> int:
    diff_path, out_path = sys.argv[1], sys.argv[2]

    base_url = os.environ["LLM_BASE_URL"].rstrip("/")
    model = os.environ["LLM_MODEL"]
    api_key = os.environ["LLM_API_KEY"]
    pr_title = os.environ.get("PR_TITLE", "")
    pr_body = os.environ.get("PR_BODY", "") or ""

    diff = load_diff(diff_path)
    user_content = (
        f"PR title: {pr_title}\n\n"
        f"PR description:\n{pr_body[:4000]}\n\n"
        f"Unified diff:\n```diff\n{diff}\n```"
    )

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ],
        "temperature": 0.2,
    }
    request = urllib.request.Request(
        f"{base_url}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as resp:
            body = json.load(resp)
    except urllib.error.HTTPError as e:
        print(f"::error::LLM request failed: HTTP {e.code}\n{e.read().decode(errors='replace')[:2000]}")
        return 1

    content = body["choices"][0]["message"]["content"]
    if not content or not content.strip():
        print("::error::LLM returned empty content")
        return 1

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(content.strip() + "\n")
    print(f"review written to {out_path} ({len(content)} chars)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
