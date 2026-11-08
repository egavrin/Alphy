#!/usr/bin/env python3
"""Capture one Alphy chat run on the disposable compose acceptance emulator."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compare import digest, encode, hex_id, require
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from run import ADB, RUNNER, adb_command, child_environment, identity, read_live_key, transfer_key

ROUTE = "alphy-android-chat"
PROVIDER = {"name": "DeepSeek", "model": "deepseek-flash",
            "config": "https://api.deepseek.com; streaming; PLAN and COMPLETE low reasoning, provider output-token default"}
PACKAGE = "com.alpha.assistant"
TEST = "com.alpha.assistant.tools.ComparisonChatCaptureTest#captureFrozenSlot"
IDENTITY_FIELDS = ("run_id", "slot_id", "plan_sha256", "request_sha256", "execution_sha256",
                   "revision", "source_sha256", "build_sha256")


def load_slot(raw, plan_hash, slot_id):
    require(digest(raw) == plan_hash, "Frozen plan hash does not match.")
    plan = json.loads(raw)
    require(plan["format"] == 1 and plan["purpose"] in {"final", "tuning"}, "Unsupported plan.")
    slots = [slot for slot in plan["slots"] if slot["id"] == slot_id]
    require(len(slots) == 1, "Select exactly one frozen slot.")
    slot = slots[0]
    repeat = slot["repeat"]
    require(slot["product"] == "candidate" and type(repeat) is int and 1 <= repeat <= 3 and
            slot_id == f"{slot['case_id']}:candidate:{repeat}" and
            re.fullmatch(r"[A-Za-z0-9:_-]{1,180}", slot_id), "Invalid Alphy slot ID.")
    cases = [case for case in plan["cases"] if case["id"] == slot["case_id"]]
    require(len(cases) == 1, "Select exactly one frozen request.")
    case = cases[0]
    request = case["request"]
    require(isinstance(request, str) and request == request.strip() and
            0 < len(request.encode("utf-16-le")) <= 32000 and "\x00" not in request,
            "The exact request must fit the Android chat composer.")
    require(digest(request.encode("utf-8")) == slot["request_sha256"], "Frozen request hash does not match.")
    require(plan["purpose"] == "final" or case["split"] != "held-out", "Held-out slots require the final plan.")
    product = plan["execution"]["products"]["candidate"]
    require(digest(encode(product)) == slot["execution_sha256"], "Frozen execution hash does not match.")
    require(product["route"] == ROUTE and product["provider"] == PROVIDER, "The frozen route or provider does not match.")
    for field, length in (("revision", 40), ("source_sha256", 64), ("build_sha256", 64)):
        require(hex_id(product[field], length), "Invalid frozen source or build identity.")
    return product, slot, case


def envelope(product, slot, case, plan_hash, run_id, review_seconds):
    return {
        "run_id": run_id, "slot_id": slot["id"], "request": case["request"], "plan_sha256": plan_hash,
        "request_sha256": slot["request_sha256"], "execution_sha256": slot["execution_sha256"],
        "revision": product["revision"], "source_sha256": product["source_sha256"],
        "build_sha256": product["build_sha256"], "review_seconds": review_seconds,
    }


def instrument_command(adb, payload):
    encoded = base64.urlsafe_b64encode(encode(payload)).decode("ascii").rstrip("=")
    return adb_command(adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
                       "-e", "comparisonEnvelope", encoded, RUNNER)


def match_report(report, payload):
    require(all(report.get(field) == payload[field] for field in IDENTITY_FIELDS),
            "The device report does not match the frozen slot.")


def adb_output(adb, *args):
    result = subprocess.run(adb_command(adb, *args), capture_output=True, text=True,
                            timeout=15, env=child_environment())
    require(result.returncode == 0, "ADB command failed.")
    return result.stdout.strip()


def installed_build(adb):
    apks = {}
    for package in (PACKAGE, PACKAGE + ".test"):
        listed = adb_output(adb, "shell", "pm", "path", package)
        require(re.fullmatch(r"package:/[A-Za-z0-9_~./+=-]+\.apk", listed),
                "Expected one installed APK for each comparison package.")
        checksum = adb_output(adb, "shell", "sha256sum", listed.removeprefix("package:")).split()
        require(checksum and hex_id(checksum[0], 64), "Installed APK hash is unavailable.")
        apks[package] = checksum[0]
    return apks


def device_identity(adb):
    return {
        "model": adb_output(adb, "shell", "getprop", "ro.product.model"),
        "os": adb_output(adb, "shell", "getprop", "ro.build.version.release"),
        "fingerprint": adb_output(adb, "shell", "getprop", "ro.build.fingerprint"),
        "size": adb_output(adb, "shell", "wm", "size"),
        "density": adb_output(adb, "shell", "wm", "density"),
    }


def capture(args):
    product, slot, case = load_slot(args.plan.read_bytes(), args.plan_sha256, args.slot)
    require(5 <= args.review_seconds <= 180, "Review duration must be 5 to 180 seconds.")
    source = identity(apks=False)
    require(source["revision"] == product["revision"] and source["sourceSha256"] == product["source_sha256"],
            "The source does not match the frozen identity.")
    apks = installed_build(args.adb)
    require(digest(encode(apks)) == product["build_sha256"], "Installed APKs do not match the frozen build.")
    device = device_identity(args.adb)
    require(all(value and value == product["device"].get(key) for key, value in device.items()),
            "Device identity or display configuration does not match the frozen values.")
    run_id = uuid.uuid4().hex
    payload = envelope(product, slot, case, args.plan_sha256, run_id, args.review_seconds)
    verify_workflow = getattr(args, "verify_workflow", False)
    if verify_workflow:
        payload["verify_workflow"] = True
    if getattr(args, "saved_edit", False):
        require(verify_workflow and case["id"] in {"extra:reference-points-ledger", "extra:russian-grid-game"},
                "A saved edit requires a frozen ledger or grid workflow.")
        payload["saved_edit"] = True
    if args.verify_tip:
        require(case["id"] == "bench:tip", "The tip interaction check requires the tip benchmark.")
        payload["verify_tip"] = True
    command = instrument_command(args.adb, payload)
    remote = f"/sdcard/Android/data/{PACKAGE}/files/comparison-capture/{run_id}"
    directory = args.output
    directory.mkdir(mode=0o700)
    manifest = {"slot": slot, "plan_sha256": args.plan_sha256, "run_id": run_id, "execution": product,
                "apks": apks, "device": device, "command": command, "status": "started"}
    manifest_path = directory / "capture.json"

    def save():
        manifest_path.write_bytes(encode(manifest))

    def spawn(command):
        return subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                env=child_environment())

    def read_report():
        try:
            report = json.loads(adb_output(args.adb, "shell", "cat", remote + "/report.json"))
        except (ValueError, subprocess.TimeoutExpired):
            return None
        match_report(report, payload)
        return report

    process = recorder = None
    transferred = False
    save()
    try:
        try:
            key = read_live_key()
        except RuntimeError as failure:
            manifest["failure"] = str(failure)
            raise
        process = spawn(command)
        workflow_seconds = 0
        if verify_workflow:
            workflow_seconds = 420 if case["id"] == "extra:reference-points-ledger" else 180
        deadline = time.monotonic() + 300 + args.review_seconds + workflow_seconds
        while process.poll() is None and time.monotonic() < deadline:
            report = read_report()
            phase = report.get("phase") if report else None
            if phase == "credentialReady" and not transferred:
                transfer_key(args.adb, run_id, key)
                transferred = True
                key = None
            if phase == "review" and recorder is None:
                recorder = spawn(adb_command(args.adb, "shell", "screenrecord", "--time-limit",
                                             str(args.review_seconds - 2), remote + "/motion.mp4"))
                print(f"Review window open for slot {slot['id']}.", flush=True)
            time.sleep(0.25)
        require(process.poll() is not None, "Device capture timed out.")
        report = read_report()
        require(report is not None, "No valid device report was available.")
        manifest["status"] = "device-" + report["phase"]
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired):
        manifest["status"] = "capture-failed"
    finally:
        key = None
        if process is not None and process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        if recorder is not None:
            try:
                recorder.wait(timeout=args.review_seconds + 5)
            except subprocess.TimeoutExpired:
                recorder.kill()
                recorder.wait(timeout=10)
        if process is not None:
            try:
                adb_output(args.adb, "pull", remote + "/.", str(directory))
            except (ValueError, OSError, subprocess.TimeoutExpired):
                manifest["status"] = "pull-failed"
        manifest["artifacts"] = {
            path.name: file_digest(path) for path in directory.iterdir()
            if path.is_file() and path != manifest_path
        }
        save()
    print(f"{manifest['status']}: {directory}")
    return 0 if manifest["status"] == "device-complete" else 1


def file_digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    show = commands.add_parser("identity", help="Read source, installed APK, and device identity without a provider call.")
    live = commands.add_parser("capture", help="Send one frozen request to DeepSeek on the disposable emulator.")
    for command in (show, live):
        command.add_argument("--adb", default=ADB)
    live.add_argument("--plan", type=Path, required=True)
    live.add_argument("--plan-sha256", required=True)
    live.add_argument("--slot", required=True)
    live.add_argument("--output", type=Path, required=True)
    live.add_argument("--review-seconds", type=int, default=20)
    live.add_argument("--verify-tip", action="store_true", help="Verify the generated tip calculator on Android.")
    live.add_argument("--verify-workflow", action="store_true", help="Run the supported frozen interaction checks on Android.")
    live.add_argument("--saved-edit", action="store_true", help="Begin the workflow with the frozen saved app.")
    args = parser.parse_args()
    try:
        if args.command == "capture":
            return capture(args)
        source = identity(apks=False)
        apks = installed_build(args.adb)
        print(encode({"revision": source["revision"], "source_sha256": source["sourceSha256"],
                      "build_sha256": digest(encode(apks)), "route": ROUTE, "provider": PROVIDER,
                      "apks": apks, "device": device_identity(args.adb)}).decode(), end="")
        return 0
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
        print("Android capture input or identity check failed.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
