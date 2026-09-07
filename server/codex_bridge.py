import glob
from collections import deque
import json
import os
import re
import shutil
import sqlite3
import sys
from datetime import datetime, timezone

HOME = os.path.expanduser("~")
CODEX = os.path.join(HOME, ".codex")
DB = os.path.join(HOME, ".cc-switch", "cc-switch.db")
CONFIG = os.path.join(CODEX, "config.toml")
AUTH = os.path.join(CODEX, "auth.json")
BACKUP_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "backups")


def read_providers():
    result = []
    connection = sqlite3.connect(DB)
    rows = connection.execute(
        "select id,name,is_current,settings_config from providers "
        "where app_type='codex' order by sort_index,name"
    ).fetchall()
    for provider_id, name, current, raw in rows:
        try:
            settings = json.loads(raw)
        except Exception:
            continue
        config = settings.get("config", "")
        model_match = re.search(r'(?m)^model\s*=\s*"([^"]+)"', config)
        model = model_match.group(1) if model_match else ""
        models = []
        catalog = settings.get("modelCatalog")
        if isinstance(catalog, dict):
            for item in catalog.get("models", []):
                if isinstance(item, dict) and item.get("model"):
                    models.append({
                        "model": str(item["model"]),
                        "displayName": str(item.get("displayName") or item["model"])
                    })
        if model and not any(x["model"] == model for x in models):
            models.insert(0, {"model": model, "displayName": model})
        result.append({
            "id": provider_id,
            "name": name,
            "isCurrent": bool(current),
            "model": model,
            "models": models,
        })
    connection.close()
    return result


def read_tasks():
    index_path = os.path.join(CODEX, "session_index.jsonl")
    tasks = {}
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8", errors="ignore") as stream:
            for line in stream:
                try:
                    item = json.loads(line)
                except Exception:
                    continue
                task_id = str(item.get("id", "")).strip()
                if not task_id:
                    continue
                old = tasks.get(task_id)
                updated = str(item.get("updated_at", ""))
                if not old or updated >= old.get("updated_at", ""):
                    tasks[task_id] = {
                        "id": task_id,
                        "title": str(item.get("thread_name", "Untitled task"))[:180],
                        "updatedAt": updated,
                    }
    return sorted(tasks.values(), key=lambda x: x["updatedAt"], reverse=True)[:40]


def find_task_file(task_id):
    pattern = os.path.join(CODEX, "sessions", "**", f"rollout-*-{task_id}.jsonl")
    matches = glob.glob(pattern, recursive=True)
    if not matches:
        raise ValueError("session rollout not found")
    return max(matches, key=os.path.getmtime)


def clipped(value, limit=500):
    return str(value or "").replace("\r", "").strip()[:limit]


def read_task(task_id):
    task_id = str(task_id).strip()
    path = find_task_file(task_id)
    title = ""
    cwd = ""
    model = ""
    started_at = ""
    last_activity_at = ""
    event_count = 0
    turn_count = 0
    input_tokens = 0
    output_tokens = 0
    completed_at = ""
    timeline = deque(maxlen=14)

    with open(path, encoding="utf-8", errors="ignore") as stream:
        for raw in stream:
            try:
                item = json.loads(raw)
            except Exception:
                continue
            kind = str(item.get("type", ""))
            timestamp = str(item.get("timestamp", ""))
            payload = item.get("payload")
            if not isinstance(payload, dict):
                continue
            if timestamp:
                last_activity_at = timestamp
            if kind == "session_meta":
                started_at = timestamp
                cwd = str(payload.get("cwd", ""))
                thread_source = str(payload.get("thread_source", ""))
                nickname = str(payload.get("agent_nickname", ""))
                timeline.append({"time": timestamp, "kind": "started", "text": f"任务启动 · 来源 {thread_source or 'desktop'}{(' · ' + nickname) if nickname else ''}"})
            elif kind == "turn_context":
                turn_count += 1
                model = str(payload.get("model", model))
                cwd = str(payload.get("cwd", cwd))
                timeline.append({"time": timestamp, "kind": "turn", "text": f"第 {turn_count} 轮上下文加载"})
            elif kind == "event_msg":
                event_type = str(payload.get("type", ""))
                event_count += 1
                if event_type == "user_message":
                    timeline.append({"time": timestamp, "kind": "user", "text": clipped(payload.get("message"), 320)})
                elif event_type == "agent_message":
                    timeline.append({"time": timestamp, "kind": "assistant", "text": clipped(payload.get("message"), 320)})
                elif event_type == "agent_reasoning":
                    timeline.append({"time": timestamp, "kind": "thinking", "text": clipped(payload.get("text"), 220)})
                elif event_type == "token_count":
                    usage = payload.get("info", {}).get("total_token_usage", {})
                    input_tokens = int(usage.get("input_tokens", input_tokens) or 0)
                    output_tokens = int(usage.get("output_tokens", output_tokens) or 0)
                elif event_type == "task_complete":
                    completed_at = timestamp
                    timeline.append({"time": timestamp, "kind": "complete", "text": "任务完成"})
            elif kind == "response_item":
                inner_type = str(payload.get("type", ""))
                if inner_type == "message" and payload.get("role") == "assistant":
                    content = payload.get("content", [])
                    text = "".join(part.get("text", "") for part in content if isinstance(part, dict))
                    if text.strip():
                        timeline.append({"time": timestamp, "kind": "assistant", "text": clipped(text, 320)})

    index_items = read_tasks()
    indexed = next((x for x in index_items if x["id"] == task_id), None)
    title = indexed["title"] if indexed else os.path.basename(path)

    if completed_at:
        status = "completed"
        progress = 100
    else:
        age_minutes = 0
        if last_activity_at:
            try:
                parsed = datetime.fromisoformat(last_activity_at.replace("Z", "+00:00"))
                age_minutes = (datetime.now(timezone.utc) - parsed).total_seconds() / 60
            except Exception:
                age_minutes = 0
        status = "active" if age_minutes <= 30 else "idle"
        progress = 92 if status == "active" else max(12, min(88, event_count // 4))

    return {
        "id": task_id,
        "title": title,
        "status": status,
        "progress": progress,
        "startedAt": started_at,
        "lastActivityAt": last_activity_at,
        "completedAt": completed_at,
        "cwd": cwd,
        "model": model,
        "eventCount": event_count,
        "turnCount": turn_count,
        "inputTokens": input_tokens,
        "outputTokens": output_tokens,
        "fileSize": os.path.getsize(path),
        "file": path,
        "timeline": list(timeline),
    }


def info():
    providers = read_providers()
    current = next((x for x in providers if x["isCurrent"]), None)
    return {
        "providers": providers,
        "tasks": read_tasks(),
        "currentProviderId": current["id"] if current else "",
        "currentProviderName": current["name"] if current else "",
        "currentModel": current["model"] if current else "",
    }


def backup():
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
    target = os.path.join(BACKUP_ROOT, stamp)
    os.makedirs(target, exist_ok=True)
    for source in (CONFIG, AUTH):
        if os.path.exists(source):
            shutil.copy2(source, os.path.join(target, os.path.basename(source)))
    return target


def select(provider_id, model=None):
    providers = read_providers()
    provider = next((x for x in providers if x["id"] == provider_id), None)
    if not provider:
        raise ValueError("provider not found")
    chosen_model = model or provider["model"]
    if model and provider["models"] and not any(x["model"] == model for x in provider["models"]):
        raise ValueError("model not in provider catalog")

    connection = sqlite3.connect(DB)
    with connection:
        connection.execute(
            "update providers set is_current=0 where app_type='codex'"
        )
        connection.execute(
            "update providers set is_current=1 where app_type='codex' and id=?",
            (provider_id,),
        )
    connection.close()

    settings = read_provider_settings(provider_id)
    config = settings.get("config", "")
    if model and provider["models"]:
        config = re.sub(r'(?m)^model\s*=\s*"[^"]+"', f'model = "{chosen_model}"', config, count=1)
    backup_dir = backup()
    with open(CONFIG, "w", encoding="utf-8") as stream:
        stream.write(config)
    auth_key = settings.get("auth", {}).get("OPENAI_API_KEY")
    if auth_key:
        atomic_json(AUTH, {"OPENAI_API_KEY": auth_key})
    return {"backup": backup_dir, "providerId": provider_id, "model": chosen_model}


def read_provider_settings(provider_id):
    connection = sqlite3.connect(DB)
    row = connection.execute(
        "select settings_config from providers where app_type='codex' and id=?",
        (provider_id,),
    ).fetchone()
    connection.close()
    if not row:
        raise ValueError("provider not found")
    return json.loads(row[0])


def atomic_json(path, value):
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as stream:
        json.dump(value, stream)
    os.replace(temp, path)


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "info"
    if action == "info":
        print(json.dumps(info(), ensure_ascii=False))
    elif action == "task":
        print(json.dumps(read_task(sys.argv[2]), ensure_ascii=False))
    elif action == "select":
        print(json.dumps(select(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None), ensure_ascii=False))


if __name__ == "__main__":
    main()
