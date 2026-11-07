#!/usr/bin/env python3
"""Resume a frozen functional capture queue without replacing evidence."""
import argparse
import fcntl
from pathlib import Path
import re
import subprocess
import sys

from compare import digest, encode, load_final_plan, make_plan, read_captures, read_json, require, validate_execution

HERE = Path(__file__).resolve().parent


def load_slots(path, plan_hash):
    raw = path.read_bytes()
    require(digest(raw) == plan_hash, "The plan does not match the frozen SHA-256.")
    plan = read_json(path)
    require(plan["format"] == 1 and plan["purpose"] in {"tuning", "final"}, "Unsupported frozen plan.")
    if plan["purpose"] == "tuning":
        require(all(case["split"] != "held-out" for case in plan["cases"]), "Held-out cases require a final plan.")
        validate_execution(plan["execution"])
        expected = make_plan(plan["cases"], plan["execution"], plan["visual_rubric"],
                             plan["approved_capabilities"], "tuning")
        require(plan["slots"] == expected["slots"], "Tuning slots differ from the frozen cases.")
    else:
        load_final_plan(argparse.Namespace(plan=path, plan_sha256=plan_hash))
    slots = {slot["id"]: slot for slot in plan["slots"]}
    require(len(slots) == len(plan["slots"]) and all(re.fullmatch(r"[A-Za-z0-9:_-]{1,180}", name) for name in slots),
            "Invalid or duplicate slot ID.")
    return plan, slots


def slot_states(slots, captures):
    states = dict.fromkeys(slots, "pending")
    for name, (capture, _) in captures.items():
        if capture["kind"] == "android":
            complete = capture["capture_status"] == "device-complete"
        else:
            complete = capture["attempts"][-1]["status"] == "complete"
        states[name] = "complete" if complete else "interrupted"
    return states


def capture_command(args, plan, slot):
    name = slot["id"]
    frozen = ["--plan", str(args.plan), "--plan-sha256", args.plan_sha256,
              "--slot", name, "--output", str(args.captures / name)]
    if slot["product"] == "candidate":
        return [sys.executable, str(HERE / "adapters/android_capture.py"), "capture", *frozen,
                "--review-seconds", str(args.review_seconds)]
    reference = plan["execution"]["products"]["reference"]
    require(args.reference_snapshot and args.node and args.typescript,
            "Reference captures require --reference-snapshot, --node, and --typescript.")
    command = [sys.executable, str(HERE / "reference_adapter/reference_capture.py"), "capture", *frozen,
               "--snapshot", args.reference_snapshot, "--revision", reference["revision"],
               "--node", args.node, "--typescript", args.typescript, "--mode", "live", "--retain-program"]
    if args.exa:
        command.append("--exa")
    return command


def run(args):
    plan, slots = load_slots(args.plan, args.plan_sha256)
    if args.slot is not None:
        require(args.slot in slots, "The requested slot is absent from the frozen plan.")
    if args.live:
        if plan["purpose"] == "final":
            require(all(case.get("interactions") for case in plan["cases"]),
                    "Freeze the interaction criteria for every case before the final run.")
        args.captures.mkdir(mode=0o700, exist_ok=True)
        require(not args.captures.is_symlink(), "Use a real capture directory.")
        with (args.captures / ".runner.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return inspect_or_capture(args, plan, slots)
    return inspect_or_capture(args, plan, slots)


def inspect_or_capture(args, plan, slots):
    captures, invalid = {}, []
    if args.captures.exists():
        captures, invalid = read_captures(args.captures, args.captures.resolve().parent, slots,
                                          plan["execution"]["products"], args.plan_sha256)
    states = slot_states(slots, captures)
    if not args.live:
        print(encode({"slots": states, "invalid_captures": invalid}).decode(), end="")
        return int(bool(invalid))
    require(not invalid, "Invalid capture evidence. Inspect the queue without --live.")
    selected = [args.slot] if args.slot else [name for name in slots if states[name] == "pending"][:args.limit]
    if not selected:
        print("No pending slots.")
    for name in selected:
        if states[name] != "pending":
            print(f"{name}: {states[name]}", flush=True)
            return int(states[name] != "complete")
        result = subprocess.run(capture_command(args, plan, slots[name]), check=False)
        if result.returncode:
            return result.returncode
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--plan-sha256", required=True)
    parser.add_argument("--captures", type=Path, required=True)
    parser.add_argument("--live", action="store_true", help="Run captures instead of inspecting the queue.")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--slot")
    selection.add_argument("--limit", type=int, default=1)
    parser.add_argument("--review-seconds", type=int, default=90)
    parser.add_argument("--reference-snapshot")
    parser.add_argument("--node")
    parser.add_argument("--typescript")
    parser.add_argument("--exa", action="store_true")
    args = parser.parse_args()
    try:
        require(args.limit > 0 and 5 <= args.review_seconds <= 180, "Use a positive limit and a review window of 5 to 180 seconds.")
        return run(args)
    except (KeyError, TypeError, ValueError, OSError) as error:
        parser.exit(2, f"{error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
