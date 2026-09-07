#!/usr/bin/env python3
"""
sync_handoff.py - Sync static markdown handoff documents to Mote runtime handoff state.

Reads HANDOFF.md, extracts structured handoff metadata (goal, currentTask, nextSteps,
keyConstraints, recentDecisions, notes), and syncs it into server/handoff.json and
optionally to the running Mote node via /api/handoff.
"""

import os
import sys
import re
import json
import time
import urllib.request
import urllib.error

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DEFAULT_HANDOFF_MD = os.path.join(ROOT_DIR, "HANDOFF.md")
SERVER_DIR = os.path.join(ROOT_DIR, "server")
HANDOFF_JSON = os.path.join(SERVER_DIR, "handoff.json")
TOKEN_FILE = os.path.join(SERVER_DIR, "access.token")


def parse_markdown_handoff(md_content: str) -> dict:
    state = {
        "goal": "",
        "currentTask": "",
        "nextSteps": "",
        "keyConstraints": "",
        "recentDecisions": "",
        "notes": "",
    }

    # Extract Goal / Project Title
    title_match = re.search(r"^#\s+(.+)$", md_content, re.MULTILINE)
    if title_match:
        state["goal"] = title_match.group(1).strip()

    # Split into markdown sections
    sections = re.split(r"\n(?=##\s+)", md_content)
    for sec in sections:
        lines = sec.strip().split("\n")
        if not lines or lines[0].startswith("# "):
            continue
        header = lines[0].lstrip("#").strip()
        body = "\n".join(lines[1:]).strip()

        if any(kw in header for kw in ["当前已验证", "当前任务", "基线", "Baseline", "Current"]):
            if not state["currentTask"]:
                state["currentTask"] = body[:500]
        elif any(kw in header for kw in ["下一步", "Next"]):
            state["nextSteps"] = body[:500]
        elif any(kw in header for kw in ["设备状态", "约束", "阻塞", "Constraint"]):
            if not state["keyConstraints"]:
                state["keyConstraints"] = body[:500]
        elif any(kw in header for kw in ["近期决定", "决定", "Decision"]):
            state["recentDecisions"] = body[:500]
        elif any(kw in header for kw in ["启动与连接", "工具", "Tooling", "Runbook", "备注", "Note"]):
            if not state["notes"]:
                state["notes"] = body[:500]

    return state


def sync(md_path: str = DEFAULT_HANDOFF_MD, dry_run: bool = False, notify_api: bool = True):
    if not os.path.exists(md_path):
        print(f"[ERROR] Handoff markdown file not found: {md_path}", file=sys.stderr)
        return False

    with open(md_path, "r", encoding="utf-8") as f:
        content = f.read()

    extracted = parse_markdown_handoff(content)
    now_ms = int(time.time() * 1000)

    # Load existing revision if available
    rev = now_ms
    if os.path.exists(HANDOFF_JSON):
        try:
            with open(HANDOFF_JSON, "r", encoding="utf-8") as f:
                old = json.load(f)
                rev = max(int(old.get("revision", 0)) + 1, now_ms)
        except Exception:
            pass

    handoff_obj = {
        "goal": extracted["goal"],
        "currentTask": extracted["currentTask"],
        "nextSteps": extracted["nextSteps"],
        "keyConstraints": extracted["keyConstraints"],
        "recentDecisions": extracted["recentDecisions"],
        "notes": extracted["notes"],
        "revision": rev,
        "updatedAtMs": now_ms,
        "updatedBy": "sync_tool",
    }

    print("=== Extracted Handoff State ===")
    print(json.dumps(handoff_obj, indent=2, ensure_ascii=False))

    if dry_run:
        print("[INFO] Dry run complete. No files modified.")
        return True

    # 1. Save to server/handoff.json
    os.makedirs(SERVER_DIR, exist_ok=True)
    temp_file = f"{HANDOFF_JSON}.{os.getpid()}.tmp"
    with open(temp_file, "w", encoding="utf-8") as f:
        json.dump(handoff_obj, f, indent=2, ensure_ascii=False)
    os.replace(temp_file, HANDOFF_JSON)
    print(f"[SUCCESS] Updated {HANDOFF_JSON} (revision: {rev})")

    # 2. Optionally notify live node via HTTP if accessible
    if notify_api:
        token = ""
        if os.path.exists(TOKEN_FILE):
            try:
                with open(TOKEN_FILE, "r", encoding="utf-8") as tf:
                    token = tf.read().strip()
            except Exception:
                pass

        for port in [9503, 9501]:
            url = f"http://127.0.0.1:{port}/api/handoff"
            req = urllib.request.Request(
                url,
                data=json.dumps({"state": handoff_obj}).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {token}" if token else "",
                },
                method="POST",
            )
            try:
                with urllib.request.urlopen(req, timeout=1.5) as resp:
                    if resp.status == 200:
                        print(f"[SUCCESS] Hot-synced to live node at port {port}")
                        break
            except Exception:
                continue

    return True


if __name__ == "__main__":
    is_dry = "--dry-run" in sys.argv
    path = DEFAULT_HANDOFF_MD
    for arg in sys.argv[1:]:
        if not arg.startswith("--"):
            path = arg
            break

    success = sync(path, dry_run=is_dry)
    sys.exit(0 if success else 1)
