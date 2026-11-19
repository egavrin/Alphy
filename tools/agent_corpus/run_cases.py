#!/usr/bin/env python3
"""Run corpus requests live through the Alphy chat on one test emulator.

Each case sends one request through the real composer with the AI provider of
ALPHY_AI_PROVIDER, waits for the chat to settle, opens the saved app, and pulls the
device evidence. The key of that provider comes from the environment or ~/.env and
goes to the device through the one-use channel. Pictures use the same key, so --images
needs an OpenAI or OpenRouter key.

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
from run import ADB, RUNNER, adb_command, child_environment, read_live_account, transfer_key  # noqa: E402

TEST = "com.alpha.assistant.tools.ComparisonChatCaptureTest#captureFrozenSlot"
PACKAGE = "com.alpha.assistant"


def sha256(data):
    return hashlib.sha256(data if isinstance(data, bytes) else data.encode("utf-8")).hexdigest()


def revision():
    return subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def adb(serial, *args, timeout=30):
    return subprocess.run(adb_command(ADB, *args, serial=serial), capture_output=True, text=True,
                          timeout=timeout, env=child_environment())


def step_timing(event):
    """Split one agent step into the wait before content, the writing, and the host checks, in milliseconds.

    The wait and the writing come from the last model call of the step. The host time is the step minus all its calls.
    """
    calls = event.get("model_calls") or []
    timing = {"tool": event.get("tool"), "ms": event.get("duration_ms"), "calls": len(calls)}
    if calls:
        last = calls[-1]
        first_text = last.get("first_text_ms")
        timing.update(wait_ms=first_text, write_ms=None if first_text is None else last["end_ms"] - first_text,
                      host_ms=event["duration_ms"] - sum(call["end_ms"] for call in calls))
    return timing


def plan_step_summary(events):
    """The phases before the first screen of one request, or None when its trace has no phase event.

    outcome and detail split the plan_outcome detail at its first colon ("outside:3d" gives "outside" and "3d"). A request
    with phases and no plan step (the switch off) has outcome None. plan_ms and first_text_ms are the ms of plan_reply_end
    and plan_first_text from the call start. guidance_ms and draft_ms are the times between the start and end phases.
    """
    phases = [event for event in events if event.get("kind") == "phase"]
    if not phases:
        return None
    first = {}
    for event in phases:
        first.setdefault(event["name"], event)

    def ms(name):
        return (first.get(name) or {}).get("ms")

    def between(start, end):
        if start not in first or end not in first:
            return None
        return (first[end]["at_ns"] - first[start]["at_ns"]) // 1_000_000

    outcome, _, detail = ((first.get("plan_outcome") or {}).get("detail") or "").partition(":")
    return {"outcome": outcome or None, "detail": detail or None, "plan_ms": ms("plan_reply_end"),
            "first_text_ms": ms("plan_first_text"),
            "compile_ms": [event.get("ms") for event in phases if event["name"] == "compile"],
            "repairs": sum(event["name"] == "repair_call_start" for event in phases),
            "guidance_ms": between("capability_guidance_start", "capability_guidance_end"),
            "draft_ms": between("draft_start", "draft_end")}


def request_summary(report):
    """The summary fields of one request: the top level of a report, or its object "second"."""
    events = report.get("operation_trace", {}).get("generation", [])
    steps = [event for event in events if event.get("kind") == "agent-step"]
    measurements = report.get("measurements", {})
    return {
        "outcome": report.get("chat_outcome"), "failure_code": report.get("failure_code"),
        "decision": report.get("chat_decision"),
        "steps": len(steps), "tools": [event.get("tool") for event in steps],
        "chat_ms": measurements.get("terminal_chat_ms"),
        "first_ui_ms": measurements.get("first_ui_ms"), "completed_ui_ms": measurements.get("completed_ui_ms"),
        "step_timing": [step_timing(event) for event in steps],
        "assistant_text": (report.get("assistant_text") or "")[:300],
        "provider_http_calls": measurements.get("provider_http_calls"),
        "plan_step": plan_step_summary(events),
    }


def report_summary(report):
    """The summary fields of a report. A repeated case adds its second request as "second"."""
    summary = dict(request_summary(report), phase=report.get("phase"))
    if "workflow" in report:
        summary["workflow"] = report["workflow"].get("status")
        summary["failure_stage"] = report.get("failure_stage")
    elif "tip_interaction" in report:
        summary["workflow"] = report["tip_interaction"].get("status")
        summary["failure_stage"] = report.get("failure_stage")
    if "second" in report:
        summary["second"] = request_summary(report["second"])
    return summary


def case_calls(summary):
    """The provider HTTP calls of one case, both requests of a repeated case included. A case with no report counts 0."""
    second = summary.get("second") or {}
    return (summary.get("provider_http_calls") or 0) + (second.get("provider_http_calls") or 0)


def run_all(cases, run, write, call_budget=None):
    """Runs the cases in order and writes each summary.

    With call_budget, the run stops before the next case when the calls of the finished cases reach it. The last summary
    then says so in stopped_at_call_budget. The cap is soft: the last case can pass it.
    """
    used = 0
    for index, case in enumerate(cases):
        summary = run(case)
        used += case_calls(summary)
        rest = cases[index + 1:]
        if call_budget is not None and used >= call_budget and rest:
            summary["stopped_at_call_budget"] = {"call_budget": call_budget, "provider_http_calls": used,
                                                 "not_run": [later["id"] for later in rest]}
            write(summary)
            return
        write(summary)


def run_case(serial, case, corpus_hash, output, chat_seconds, review_seconds, verify_workflow=False, saved_edit=False,
             images=False, screenshot_review=False, plan_step=False, repeat=1):
    run_id = uuid.uuid4().hex
    payload = {
        "run_id": run_id, "slot_id": case["id"] + ":candidate:1", "request": case["request"],
        "review_seconds": review_seconds, "chat_seconds": chat_seconds,
        "plan_sha256": corpus_hash, "request_sha256": sha256(case["request"]),
        "execution_sha256": sha256("agent-corpus"), "revision": revision(),
        "source_sha256": sha256("agent-corpus-source"), "build_sha256": sha256("agent-corpus-build"),
        "plan_step": plan_step, "repeat": repeat,
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
    account = read_live_account()
    deadline = started + repeat * chat_seconds + review_seconds + 120
    try:
        while process.poll() is None and time.monotonic() < deadline:
            if account is not None:
                result = adb(serial, "shell", "cat", remote + "/report.json")
                if result.returncode == 0:
                    try:
                        phase = json.loads(result.stdout).get("phase")
                    except ValueError:
                        phase = None
                    if phase == "credentialReady":
                        transfer_key(ADB, run_id, account, serial=serial)
                        account = None
            time.sleep(0.5)
    finally:
        account = None
        if process.poll() is None:
            process.kill()
        stdout = process.communicate(timeout=30)[0].decode("utf-8", "replace")
    (directory / "instrumentation.txt").write_text(stdout)
    adb(serial, "pull", remote + "/.", str(directory), timeout=120)
    adb(serial, "shell", "rm", "-rf", remote)
    summary = {"case": case["id"], "expected": case["expected_outcome"], "seconds": round(time.monotonic() - started),
               "images": images, "screenshot_review": screenshot_review, "plan_step_switch": plan_step, "repeat": repeat}
    report_path = directory / "report.json"
    if report_path.exists():
        summary.update(report_summary(json.loads(report_path.read_text())))
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
                        help="Turn on pictures with the key of the AI provider. Each generation makes at most 4 pictures.")
    parser.add_argument("--screenshot-review", action="store_true", help="Turn on the optional screenshot review.")
    parser.add_argument("--plan-step", choices=["on", "off"], default="off",
                        help="The switch of the plan step for the provider of the account during each case.")
    parser.add_argument("--repeat", type=int, choices=[1, 2], default=1,
                        help="2 sends each request once more in a new conversation of the same process (a warm request).")
    parser.add_argument("--call-budget", type=int,
                        help="Stop before the next case when the provider HTTP calls of the finished cases reach N.")
    parser.add_argument("cases", nargs="*", help="Case IDs. Empty runs every case.")
    args = parser.parse_args()
    if args.saved_edit != bool(args.request) or args.saved_edit and not args.verify_workflow:
        parser.error("--saved-edit requires --verify-workflow and --request, and --request requires --saved-edit")
    if args.repeat == 2 and args.verify_workflow:
        parser.error("--repeat 2 does not run with --verify-workflow")
    if args.call_budget is not None and args.call_budget < 1:
        parser.error("--call-budget must be 1 or more")
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
        def write(summary):
            stream.write(json.dumps(summary, ensure_ascii=False) + "\n")
            stream.flush()
            print(json.dumps(summary, ensure_ascii=False), flush=True)

        run_all(cases, lambda case: run_case(args.serial, case, sha256(raw), args.output, args.chat_seconds,
                                             args.review_seconds, args.verify_workflow, args.saved_edit, args.images,
                                             args.screenshot_review, args.plan_step == "on", args.repeat),
                write, args.call_budget)


if __name__ == "__main__":
    main()
