#!/usr/bin/env python3
"""Narrow repository guardrails, NOT a dependency-vulnerability scan or penetration test."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
failures = []
patterns = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
}
for path in ROOT.rglob("*"):
    if not path.is_file() or any(p in {".git", "target", ".tools", "data"} for p in path.parts):
        continue
    if path.suffix in {".jar", ".zip", ".png", ".jpg", ".pdf"}:
        continue
    try:
        text = path.read_text()
    except (UnicodeDecodeError, OSError):
        continue
    for label, pattern in patterns.items():
        if pattern.search(text):
            failures.append(f"{path.relative_to(ROOT)}: possible {label}")
for path in (ROOT / "src/main/java").rglob("*.java"):
    text = path.read_text()
    if re.search(r"Runtime\.getRuntime\(\)\.exec|new ProcessBuilder|\.openConnection\(|\.openStream\(", text):
        failures.append(f"{path.relative_to(ROOT)}: unexpected process or destination-fetch capability")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print("PASS: narrow secret-pattern and forbidden execution/destination-fetch guardrails")
print("Scope: does not establish absence of vulnerabilities; review dependencies and deployment before release.")
