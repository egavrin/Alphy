#!/usr/bin/env python3
"""Run corpus requests live through the Alphy chat on one test emulator.

Each case sends one request through the real composer with DeepSeek, waits for the
chat to settle, opens the saved app, and pulls the device evidence. The key comes
from DEEPSEEK_API_KEY or ~/.env and goes to the device through the one-use channel.

Build and install the debug and Android test APKs on the emulator first.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/compose_acceptance"))
from run import ADB, RUNNER, adb_command, child_environment, read_live_key, transfer_key  # noqa: E402

TEST = "com.alpha.assistant.tools.ComparisonChatCaptureTest#captureFrozenSlot"
PACKAGE = "com.alpha.assistant"


def sha256(data):
    return hashlib.sha256(data if isinstance(data, bytes) else data.encode("utf-8")).hexdigest()


def revision():
    return subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def adb(serial, *args, timeout=30):
    return subprocess.run(adb_command(ADB, *args, serial=serial), capture_output=True, text=True,
                          timeout=timeout, env=child_environment())


def run_case(serial, case, corpus_hash, output, chat_seconds, review_seconds, verify_workflow=False, saved_edit=False,
             images=False, screenshot_review=False):
    run_id = uuid.uuid4().hex
    payload = {
        "run_id": run_id, "slot_id": case["id"] + ":candidate:1", "request": case["request"],
        "review_seconds": review_seconds, "chat_seconds": chat_seconds,
        "plan_sha256": corpus_hash, "request_sha256": sha256(case["request"]),
        "execution_sha256": sha256("agent-corpus"), "revision": revision(),
        "source_sha256": sha256("agent-corpus-source"), "build_sha256": sha256("agent-corpus-build"),
    }
    if verify_workflow:
        payload["verify_workflow"] = True
    if saved_edit:
        payload["saved_edit"] = True
    if images:
        payload["images"] = True
    if screenshot_review:
        payload["screenshot_review"] = True
    encoded = base64.urlsafe_b64encode(json.dumps(payload).encode("utf-8")).decode("ascii").rstrip("=")
    command = adb_command(ADB, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
                          "-e", "comparisonEnvelope", encoded, RUNNER, serial=serial)
    remote = f"/sdcard/Android/data/{PACKAGE}/files/comparison-capture/{run_id}"
    directory = output / case["id"].replace(":", "_")
    directory.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=child_environment())
    key = read_live_key()
    image_key = read_live_key(name="OPENROUTER_API_KEY") if images else None
    transferred = False
    deadline = started + chat_seconds + review_seconds + 120
    try:
        while process.poll() is None and time.monotonic() < deadline:
            if not transferred:
                result = adb(serial, "shell", "cat", remote + "/report.json")
                if result.returncode == 0:
                    try:
                        phase = json.loads(result.stdout).get("phase")
                    except ValueError:
                        phase = None
                    if phase == "credentialReady" and key is not None:
                        transfer_key(ADB, run_id, key, serial=serial)
                        key = None
                        transferred = image_key is None
                    elif phase == "imageCredentialReady" and image_key is not None:
                        transfer_key(ADB, run_id + "-openrouter", image_key, serial=serial)
                        image_key = None
                        transferred = True
            time.sleep(0.5)
    finally:
        key = image_key = None
        if process.poll() is None:
            process.kill()
        stdout = process.communicate(timeout=30)[0].decode("utf-8", "replace")
    (directory / "instrumentation.txt").write_text(stdout)
    adb(serial, "pull", remote + "/.", str(directory), timeout=120)
    adb(serial, "shell", "rm", "-rf", remote)
    summary = {"case": case["id"], "expected": case["expected_outcome"], "seconds": round(time.monotonic() - started),
               "images": images, "screenshot_review": screenshot_review}
    report_path = directory / "report.json"
    if report_path.exists():
        report = json.loads(report_path.read_text())
        steps = [event for event in report.get("operation_trace", {}).get("generation", []) if event.get("kind") == "agent-step"]
        summary.update({
            "outcome": report.get("chat_outcome"), "failure_code": report.get("failure_code"),
            "decision": report.get("chat_decision"), "phase": report.get("phase"),
            "steps": len(steps), "tools": [event.get("tool") for event in steps],
            "chat_ms": report.get("measurements", {}).get("terminal_chat_ms"),
            "assistant_text": (report.get("assistant_text") or "")[:300],
        })
        if "workflow" in report:
            summary["workflow"] = report["workflow"].get("status")
            summary["failure_stage"] = report.get("failure_stage")
        elif "tip_interaction" in report:
            summary["workflow"] = report["tip_interaction"].get("status")
            summary["failure_stage"] = report.get("failure_stage")
        artifact = directory / "artifact.json"
        if artifact.exists():
            summary["title"] = json.loads(artifact.read_text())["spec"].get("title")
    else:
        summary["outcome"] = "no-report"
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--chat-seconds", type=int, default=420)
    parser.add_argument("--review-seconds", type=int, default=5)
    parser.add_argument("--shard", default="0/1", help="K/N runs every Nth selected case, starting at index K.")
    parser.add_argument("--verify-workflow", action="store_true",
                        help="Also run the host UI workflow checks. Use only with cases that have one.")
    parser.add_argument("--saved-edit", action="store_true",
                        help="Start from the frozen saved app of the case and send --request as a follow-up edit inside the "
                             "workflow check. Requires --verify-workflow and --request.")
    parser.add_argument("--request", help="The follow-up edit text for --saved-edit. It replaces the corpus request.")
    parser.add_argument("--images", action="store_true",
                        help="Turn on pictures. OPENROUTER_API_KEY goes to the device through a second one-use transfer. "
                             "Each generation makes at most 4 pictures.")
    parser.add_argument("--screenshot-review", action="store_true", help="Turn on the optional screenshot review.")
    parser.add_argument("cases", nargs="*", help="Case IDs. Empty runs every case.")
    args = parser.parse_args()
    if args.saved_edit != bool(args.request) or args.saved_edit and not args.verify_workflow:
        parser.error("--saved-edit requires --verify-workflow and --request, and --request requires --saved-edit")
    raw = args.corpus.read_bytes()
    cases = json.loads(raw)["cases"]
    if args.cases:
        wanted = set(args.cases)
        cases = [case for case in cases if case["id"] in wanted]
        missing = wanted - {case["id"] for case in cases}
        if missing:
            raise SystemExit(f"Unknown cases: {sorted(missing)}")
    if args.request:
        cases = [dict(case, request=args.request) for case in cases]
    index, count = (int(part) for part in args.shard.split("/"))
    cases = cases[index::count]
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / f"summary-{args.serial}.jsonl").open("a") as stream:
        for case in cases:
            summary = run_case(args.serial, case, sha256(raw), args.output, args.chat_seconds, args.review_seconds,
                               args.verify_workflow, args.saved_edit, args.images, args.screenshot_review)
            stream.write(json.dumps(summary, ensure_ascii=False) + "\n")
            stream.flush()
            print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
