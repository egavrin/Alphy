#!/usr/bin/env python3
"""Explicit-device evidence with a one-use, in-memory credential channel for live runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import socket
import struct
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = ROOT / "app/src/androidTest/assets/compose-scenarios.json"
ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
APKS = ("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
BUILD_ENVIRONMENT = {"JAVA_HOME": "/opt/homebrew/opt/openjdk@17", "ANDROID_HOME": "/opt/homebrew/share/android-commandlinetools"}
RUNNER = "com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner"


def scenarios():
    return json.loads(SCENARIOS.read_text())["scenarios"]


def matrix(cases):
    return [(case["id"], repeat) for case in cases for repeat in range(1, 4)]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def adb_command(adb, *args, serial="emulator-5556"):
    if not re.fullmatch(r"emulator-[0-9]{4,5}", serial):
        raise SystemExit("Use an explicit emulator serial, such as emulator-5556.")
    return [adb, "-s", serial, *args]


def child_environment():
    return {key: value for key, value in os.environ.items() if key != "DEEPSEEK_API_KEY"}


def read_live_key(environment=None):
    environment = os.environ if environment is None else environment
    if environment.get("DEEPSEEK_API_KEY"):
        return environment["DEEPSEEK_API_KEY"]
    try:
        lines = (Path.home() / ".env").read_text().splitlines()
    except FileNotFoundError:
        lines = []
    except OSError:
        raise RuntimeError("Cannot read DEEPSEEK_API_KEY from ~/.env.") from None
    for line in lines:
        match = re.match(r"^\s*(?:export\s+)?DEEPSEEK_API_KEY\s*=\s*(.*)$", line)
        if match:
            try:
                values = shlex.split(match.group(1), comments=True)
            except ValueError:
                raise RuntimeError("Cannot parse the DEEPSEEK_API_KEY dotenv entry.") from None
            if len(values) == 1 and values[0]:
                return values[0]
    raise RuntimeError("DEEPSEEK_API_KEY is absent from the process environment and ~/.env.")


def transfer_key(adb, run_id, key, *, serial="emulator-5556"):
    payload = bytearray(key.encode("utf-8"))
    if not 1 <= len(payload) <= 8192 or not key.strip() or "\n" in key or "\r" in key:
        raise RuntimeError("DEEPSEEK_API_KEY has an invalid credential envelope.")
    port = None
    try:
        result = subprocess.run(adb_command(adb, "forward", "tcp:0", "localabstract:compose-credential-" + run_id, serial=serial),
                                check=True, capture_output=True, text=True, env=child_environment())
        port = int(result.stdout.strip())
        with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
            connection.sendall(struct.pack("!I", len(payload)) + payload)
            if connection.recv(1) != b"\x01":
                raise RuntimeError("Credential receiver did not acknowledge the transfer.")
    except Exception:
        raise RuntimeError("One-use credential transfer failed.") from None
    finally:
        payload[:] = b"\x00" * len(payload)
        if port is not None:
            removed = subprocess.run(adb_command(adb, "forward", "--remove", "tcp:" + str(port), serial=serial),
                                     capture_output=True, env=child_environment())
            if removed.returncode != 0:
                raise RuntimeError("Credential forward cleanup failed.")


def instrument_command(adb, scenario, repetition, revision, source_hash, live, seconds, run_id="test", credential_probe=False):
    args = {"class": "com.alpha.assistant.tools.ComposeScenarioAcceptanceTest#generateAndPresentIndependentScenario",
            "composeLive": str(live).lower(), "composeScenario": str(scenario), "composeRepetition": str(repetition),
            "composeRevision": revision, "composeSourceSha256": source_hash, "composeRunId": run_id,
            "composeReviewSeconds": str(seconds), "composeCredentialProbe": str(credential_probe).lower()}
    if live or credential_probe:
        args["composeCredentialTransport"] = "adb"
    command = adb_command(adb, "shell", "am", "instrument", "-w", "-r")
    for key, value in args.items():
        command += ["-e", key, value]
    return command + [RUNNER]



def run_course(args):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", args.label):
        raise SystemExit("Use a course evidence label with letters, numbers, dots, dashes, or underscores.")
    inputs = json.loads((ROOT / "app/src/androidTest/assets/course-scenarios.json").read_text())
    run_id = "course-" + uuid.uuid4().hex[:12]
    directory = args.output / args.label / run_id
    directory.mkdir(parents=True, exist_ok=False)
    manifest = {
        "scenario": args.scenario, "input": inputs[args.scenario], "serial": args.serial,
        "baseline": args.baseline, "status": "failed",
    }
    key = process = None
    remote = "/sdcard/Android/data/com.alpha.assistant/files/course-acceptance/" + run_id

    def device(*command):
        return adb_command(args.adb, *command, serial=args.serial)

    def read_device(*command):
        return subprocess.run(device(*command), check=True, capture_output=True, text=True,
                              timeout=20, env=child_environment()).stdout.strip()

    try:
        tested = identity()
        (directory / "identity.json").write_text(json.dumps(tested, indent=2) + "\n")
        if read_device("shell", "getprop", "ro.kernel.qemu") != "1":
            raise RuntimeError("The selected device is not an Android emulator.")
        key = read_live_key()
        with (directory / "device.log").open("w") as log:
            commands = []
            installed = {
                package: installed_apk_hash(args.adb, args.serial, package, commands, log)
                for package in ("com.alpha.assistant", "com.alpha.assistant.test")
            }
        manifest["installed"] = installed
        if list(installed.values()) != list(tested["apks"].values()):
            raise RuntimeError("Installed APK hashes differ from the selected build.")
        manifest["device"] = {
            "fingerprint": read_device("shell", "getprop", "ro.build.fingerprint"),
            "api": read_device("shell", "getprop", "ro.build.version.sdk"),
            "size": read_device("shell", "wm", "size"),
            "density": read_device("shell", "wm", "density"),
            "fontScale": read_device("shell", "settings", "get", "system", "font_scale"),
        }
        command = device("shell", "am", "instrument", "-w", "-r",
                         "-e", "class", "com.alpha.assistant.courses.LiveCourseWorkflowTest#generateAndPlay",
                         "-e", "courseRunId", run_id, "-e", "courseScenario", args.scenario,
                         "-e", "courseBaseline", str(args.baseline).lower(), RUNNER)
        manifest["command"] = command
        with (directory / "instrumentation.log").open("w") as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=child_environment())
            deadline = time.monotonic() + 1500
            while process.poll() is None and time.monotonic() < deadline:
                if key is not None:
                    probe = subprocess.run(device("shell", "cat", remote + "/report.json"), capture_output=True,
                                           text=True, timeout=20, env=child_environment())
                    if probe.returncode == 0 and json.loads(probe.stdout).get("phase") == "credentialReady":
                        transfer_key(args.adb, run_id, key, serial=args.serial)
                        key = None
                time.sleep(0.25 if key is not None else 1)
            if process.poll() is None:
                raise RuntimeError("Course workflow timed out.")
        read_device("pull", remote + "/.", str(directory))
        report = json.loads((directory / "report.json").read_text())
        instrumentation = (directory / "instrumentation.log").read_text()
        if process.returncode == 0 and report["phase"] == "complete" and "OK (1 test)" in instrumentation:
            manifest["status"] = "passed"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failure:
        manifest["failure"] = str(failure)
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
                read_device("shell", "am", "force-stop", "com.alpha.assistant")
                read_device("pull", remote + "/.", str(directory))
            except (OSError, subprocess.SubprocessError):
                manifest["cleanup"] = "Device cleanup or evidence pull failed."
        (directory / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print(f"{manifest['status']}: {directory}", flush=True)
    return 0 if manifest["status"] == "passed" else 1


def validate_results(case, rows):
    if len(rows) != len(case["checks"]) or {row["id"] for row in rows} != set(case["checks"]):
        raise ValueError("Each required interaction needs exactly one result.")
    for row in rows:
        if row["status"] not in {"PASS", "FAIL", "UNAVAILABLE"} or not all(row.get(key) for key in ("expected", "observed", "evidence")):
            raise ValueError("Each result needs status, expected result, observed result, and evidence.")
    return all(row["status"] == "PASS" for row in rows)


def validate_matrix(rows):
    expected = set(matrix(scenarios()))
    if len(rows) != 18 or {(row["scenario"], row["repetition"]) for row in rows} != expected:
        raise ValueError("The evidence must cover every one of the 18 independent matrix slots exactly once.")
    for key in ("artifactId", "database"):
        if any(not row.get(key) for row in rows) or len({row[key] for row in rows}) != 18:
            raise ValueError("Independent runs must have 18 distinct " + key + " values.")
    if any(row["mode"] != "real" for row in rows):
        raise ValueError("Synthetic runs cannot complete the real matrix.")


def identity(apks=True):
    listed = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "tools/compose_acceptance"],
        cwd=ROOT, env=child_environment(),
    )
    paths = {ROOT / os.fsdecode(name) for name in listed.split(b"\0") if name}
    # Android packages assets from app/src even when Git ignores them.
    paths.update((ROOT / "app/src").rglob("*"))
    paths = sorted(path for path in paths if path.is_file() and "__pycache__" not in path.parts)
    paths += [ROOT / "app/build.gradle.kts", ROOT / "docs/parity/compose-coverage.json", SCENARIOS]
    files = {str(path.relative_to(ROOT)): digest(path) for path in paths}
    aggregate = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    tested = {"revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, env=child_environment()).strip(),
              "sourceSha256": aggregate, "files": files}
    if apks:
        tested["apks"] = {path: digest(ROOT / path) for path in APKS}
    return tested


def run(args):
    case = next(case for case in scenarios() if case["id"] == args.scenario)
    mode = "credential-check" if args.credential_check else "real" if args.live else "synthetic"
    key = "synthetic-credential-" + uuid.uuid4().hex if args.credential_check else read_live_key() if args.live else None
    probe_secret = key if args.credential_check else None
    run_id = f"s{args.scenario}-r{args.repetition}-{uuid.uuid4().hex[:12]}"
    directory = args.output / mode / run_id
    directory.mkdir(parents=True, exist_ok=False)
    tested = identity()
    (directory / "identity.json").write_text(json.dumps(tested, indent=2) + "\n")
    remote = f"/sdcard/Android/data/com.alpha.assistant/files/compose-acceptance/{mode}/{run_id}"
    command = instrument_command(args.adb, args.scenario, args.repetition, tested["revision"], tested["sourceSha256"], args.live, args.seconds, run_id, args.credential_check)
    manifest = {"runId": run_id, "mode": mode, "scenario": args.scenario, "repetition": args.repetition,
                "request": case["request"], "command": command, "serial": "emulator-5556",
                "expected": "A normal independent generation, native render, and explicit interaction evidence.",
                "observed": "PENDING", "checks": [{"id": key, "status": "UNAVAILABLE", "expected": value,
                "observed": "Not reviewed", "evidence": ["report.json"]} for key, value in case["checks"].items()]}
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    subprocess.run(adb_command(args.adb, "get-state"), check=True, capture_output=True, env=child_environment())
    recorder = None
    transfer_failed = False
    with (directory / "instrumentation.log").open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT, env=child_environment())
        deadline = time.monotonic() + 600 + args.seconds
        while process.poll() is None and time.monotonic() < deadline:
            probe = subprocess.run(adb_command(args.adb, "shell", "cat", remote + "/report.json"), capture_output=True, text=True, env=child_environment())
            if recorder is None and probe.returncode == 0:
                report = json.loads(probe.stdout)
                if report.get("phase") == "credentialReady" and key is not None:
                    try:
                        transfer_key(args.adb, run_id, key)
                    except RuntimeError:
                        transfer_failed = True
                        manifest["credentialTransfer"] = "failed"
                    finally:
                        key = None
                if report.get("phase") == "review":
                    recorder = subprocess.Popen(adb_command(args.adb, "shell", "screenrecord", "--time-limit", str(min(args.seconds, 180)), remote + "/motion.mp4"), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=child_environment())
                    print(f"Review window: {args.seconds} seconds. Test-owned app: {run_id}", flush=True)
            time.sleep(1)
        if process.poll() is None:
            process.terminate()
            manifest["observed"] = "TIMEOUT"
        else:
            manifest["observed"] = "Instrumentation finished; inspect report and interaction checks."
        process.wait(timeout=10)
    if recorder is not None:
        try:
            recorder.wait(timeout=190)
        except subprocess.TimeoutExpired:
            recorder.terminate()
            recorder.wait(timeout=10)
    pulled = subprocess.run(adb_command(args.adb, "pull", remote + "/.", str(directory)), capture_output=True, text=True, env=child_environment())
    (directory / "capture.log").write_text(pulled.stdout + pulled.stderr)
    manifest["artifacts"] = {path.name: digest(path) for path in directory.iterdir() if path.is_file() and path.name != "manifest.json"}
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    if probe_secret is not None and any(probe_secret.encode() in path.read_bytes() for path in directory.iterdir() if path.is_file()):
        raise RuntimeError("The synthetic credential appeared in captured evidence.")
    print(directory)
    report_path = directory / "report.json"
    if transfer_failed or not report_path.exists() or json.loads(report_path.read_text()).get("phase") != "complete":
        raise SystemExit("Generation or capture failed. Evidence remains in the run directory.")


def validate_run_identity(manifest, report):
    for record in (manifest, report):
        if type(record.get("scenario")) is not int or record["scenario"] not in range(1, 7):
            raise ValueError("Run scenario is outside the canonical matrix.")
        if type(record.get("repetition")) is not int or record["repetition"] not in range(1, 4):
            raise ValueError("Run repetition is outside the canonical matrix.")
        if not isinstance(record.get("runId"), str) or not record["runId"].strip():
            raise ValueError("Run identity is absent.")
    for field in ("scenario", "repetition", "runId", "request"):
        if field not in manifest or field not in report or manifest[field] != report[field]:
            raise ValueError("Manifest and report differ: " + field)
    case = next(case for case in scenarios() if case["id"] == manifest["scenario"])
    if manifest["request"] != case["request"]:
        raise ValueError("Run request differs from the exact canonical request.")
    return case


def verify(directory):
    manifest = json.loads((directory / "manifest.json").read_text())
    report = json.loads((directory / "report.json").read_text())
    case = validate_run_identity(manifest, report)
    for name, expected in manifest["artifacts"].items():
        if digest(directory / name) != expected:
            raise ValueError("Evidence hash differs: " + name)
    for name in ("before.png", "after.png", "motion.mp4", "identity.json"):
        if not (directory / name).is_file() or (directory / name).stat().st_size == 0:
            raise ValueError("Missing evidence: " + name)
    if report.get("generation") != "accepted" or report.get("phase") != "complete" or report.get("mode") != manifest["mode"]:
        raise ValueError("Generation evidence does not match the manifest.")
    tested = json.loads((directory / "identity.json").read_text())
    if any(report.get(key) != tested[key] for key in ("revision", "sourceSha256")):
        raise ValueError("Tested source identity differs.")
    if report.get("specSha256") != digest(directory / "artifact.json"):
        raise ValueError("Artifact identity differs.")
    for row in manifest["checks"]:
        for name in row["evidence"]:
            path = (directory / name).resolve()
            if directory.resolve() not in path.parents or not path.is_file():
                raise ValueError("Interaction evidence is absent or outside this run.")
    passed = validate_results(case, manifest["checks"])
    print("PASS" if passed and manifest["mode"] == "real" else "INCOMPLETE_OR_SYNTHETIC")
    return passed and manifest["mode"] == "real"


def validate_failed_generation(attempts, failure_type):
    failure_outcomes = {"rejected", "providerFailure", "runtimeUnavailable", "canceled"}
    candidate_stages = {"provider", "definition", "state", "smoke", "behavior"}
    if not isinstance(attempts, list) or not attempts or any(
        not isinstance(row, dict) or type(row.get("attempt")) is not int or any(
            not isinstance(row.get(key), str) or not row[key]
            for key in ("outcome", "stage", "failureCode", "category")
        ) for row in attempts
    ):
        raise ValueError("Failed attempt identity or terminal diagnostic is incomplete.")

    # Historical reports recorded only terminal candidate attempts.
    if all("disposition" not in row and "selectorBatch" not in row for row in attempts):
        if len(attempts) > 2 or any(
            row["attempt"] != number or row["outcome"] not in failure_outcomes
            or row["stage"] not in candidate_stages | {"availability"}
            for number, row in enumerate(attempts, 1)
        ):
            raise ValueError("Failed attempt identity or terminal diagnostic is incomplete.")
        if len(attempts) == 2 and attempts[0]["outcome"] != "rejected":
            raise ValueError("Only rejected model output permits a correction.")
        return

    number = 1
    plan_repairs = 0
    writer_repairs = 0
    phase = "plan"
    next_batch = "sections"
    parts_completed = False
    transport_failed = False
    fallback = False
    for index, row in enumerate(attempts):
        stage, outcome, code = row["stage"], row["outcome"], row["failureCode"]
        disposition = row.get("disposition")
        batch = row.get("selectorBatch")
        valid = (row["attempt"] == number
                 and disposition in ("continue", "correct", "finish")
                 and (disposition == "finish") == (index == len(attempts) - 1)
                 and (code == "none") == (outcome in ("completed", "validated"))
                 and ("selectorBatch" not in row or (stage == "selector" and batch in ("sections", "parts"))))
        if stage == "availability":
            valid = valid and index == 0 and disposition == "finish" and outcome in ("runtimeUnavailable", "canceled")
        elif stage == "plan":
            correctable = (outcome, code) in (
                ("rejected", "widget_plan"), ("rejected", "widget_patch"), ("providerFailure", "response_length")
            )
            valid = valid and phase == "plan" and (
                (outcome == "completed" and disposition == "continue")
                or (correctable and plan_repairs < 3 and disposition == "correct")
                or (disposition == "finish" and (outcome in ("providerFailure", "canceled") or correctable)
                    and (not correctable or plan_repairs > 0))
            )
            if disposition == "continue":
                phase = "selector"
            elif disposition == "correct":
                plan_repairs += 1
        elif stage == "selector":
            valid = valid and phase == "selector"
            if batch is not None:
                valid = valid and batch == next_batch and not fallback and (
                    (disposition == "continue" and outcome in ("completed", "providerFailure"))
                    or (disposition == "finish" and outcome == "canceled" and code == "cancelled")
                )
                next_batch = "parts" if batch == "sections" and outcome == "completed" else None
                parts_completed = batch == "parts" and outcome == "completed"
                transport_failed = outcome == "providerFailure"
            elif disposition == "continue":
                valid = valid and next_batch != "sections" and not fallback and (outcome, code) == ("rejected", "widget_selector")
                fallback = True
            else:
                valid = valid and disposition == "finish" and (
                    (outcome, code) in (("canceled", "cancelled"), ("providerFailure", "unknown"))
                    or (next_batch != "sections" and (not transport_failed or fallback)
                        and (outcome, code) == ("rejected", "widget_questions"))
                )
        elif stage == "assembly":
            valid = (valid and phase == "selector" and parts_completed and disposition == "finish"
                     and (outcome, code) == ("rejected", "widget_incomplete"))
        elif stage in candidate_stages:
            valid = valid and phase in ("selector", "candidate") and (not transport_failed or fallback)
            phase = "candidate"
            if disposition == "correct":
                valid = valid and writer_repairs < 3 and (
                    (stage != "provider" and outcome == "rejected")
                    or (stage == "provider" and (outcome, code) in (
                        ("providerFailure", "response_length"), ("rejected", "widget_patch")
                    ))
                )
                writer_repairs += 1
            elif outcome == "validated":
                # Candidate validation can finish before preview or artifact publication fails.
                valid = (valid and disposition == "finish" and stage in {"smoke", "behavior"}
                         and isinstance(failure_type, str) and bool(failure_type))
            else:
                valid = valid and disposition == "finish" and outcome in failure_outcomes
        else:
            valid = False
        if not valid:
            raise ValueError("Failed generation diagnostic stage, outcome, or correction order is invalid.")
        if disposition == "correct":
            number += 1


def verify_failed(directory):
    """Validate a complete failed generation record. This never verifies workflow success."""
    manifest = json.loads((directory / "manifest.json").read_text())
    report = json.loads((directory / "report.json").read_text())
    case = validate_run_identity(manifest, report)
    if report.get("generation") != "failed" or report.get("phase") != "failed" or report.get("mode") != manifest.get("mode"):
        raise ValueError("A failed generation record is required.")
    if manifest.get("mode") not in {"real", "synthetic"}:
        raise ValueError("Credential probes are not generation evidence.")
    required = {"report.json", "identity.json", "instrumentation.log", "capture.log"}
    if not required.issubset(manifest.get("artifacts", {})):
        raise ValueError("Failed evidence needs hashed report, source identity, instrumentation, and capture records.")
    for name, expected in manifest["artifacts"].items():
        path = (directory / name).resolve()
        if directory.resolve() not in path.parents or not path.is_file() or path.stat().st_size == 0:
            raise ValueError("Failed evidence is absent or outside this run.")
        if digest(path) != expected:
            raise ValueError("Evidence hash differs: " + name)
    tested = json.loads((directory / "identity.json").read_text())
    if any(report.get(key) != tested.get(key) or not tested.get(key) for key in ("revision", "sourceSha256")):
        raise ValueError("Tested source identity differs.")
    validate_failed_generation(report.get("generationAttempts"), report.get("failureType"))
    rows = manifest["checks"]
    validate_results(case, rows)
    if any(row["status"] == "PASS" for row in rows):
        raise ValueError("Failed generation cannot prove an interaction.")
    for row in rows:
        for name in row["evidence"]:
            if name not in manifest["artifacts"]:
                raise ValueError("Failed interaction evidence must be captured and hashed.")
    print("FAILED: complete generation-failure evidence; workflow remains failed.")
    return "FAILED"


def suite_outcome(stdout, known, known_skipped=frozenset()):
    """Check AndroidJUnitRunner status records against the final JUnit summary."""
    status, started, finished = {}, {}, {}
    failures, skipped, totals = set(), set(), set()
    invalid_status = False
    for line in stdout.splitlines():
        kind, _, value = line.strip().partition(": ")
        if kind == "INSTRUMENTATION_STATUS":
            key, _, value = value.partition("=")
            status[key] = value
        elif kind == "INSTRUMENTATION_STATUS_CODE":
            event, status = status, {}
            if "current" not in event and value == "0":
                continue
            if not (event.get("class") and event.get("test") and event.get("current", "").isdigit()
                    and event.get("numtests", "").isdigit()):
                invalid_status = True
                continue
            index = int(event["current"])
            test = event["class"] + "#" + event["test"]
            totals.add(int(event["numtests"]))
            if value == "1" and index not in started:
                started[index] = test
            elif value in {"0", "-1", "-2", "-3", "-4"} and started.get(index) == test and index not in finished:
                finished[index] = value
                if value in ("-1", "-2"):
                    failures.add(test)
                if value in ("-3", "-4"):
                    skipped.add(test)
            else:
                invalid_status = True
    summaries = re.findall(r"^(?:OK \((\d+) tests?\)|Tests run: (\d+),\s+Failures: (\d+))\s*$", stdout, re.M)
    success_count, test_count, failure_count = summaries[-1] if summaries else ("", "", "")
    tests = int(success_count or test_count or 0)
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", stdout, re.M)
    expected_indices = set(range(1, len(finished) + 1))
    passed = (not invalid_status and len(summaries) == 1 and terminal == ["-1"] and tests > 0
              and started.keys() == finished.keys() == expected_indices and totals == {len(finished)}
              and tests == len(finished) - sum(code == "-3" for code in finished.values())
              and int(failure_count or 0) == sum(code in ("-1", "-2") for code in finished.values())
              and failures <= set(known) and skipped <= set(known_skipped))
    return {"observed": "passed" if passed else "failed", "tests": tests,
            "failures": sorted(failures), "skipped": sorted(skipped)}


def suite_instrument_command(adb, targets, revision, *, serial="emulator-5556"):
    command = adb_command(adb, "shell", "am", "instrument", "-w", "-r", serial=serial)
    if targets:
        command += ["-e", "class", ",".join(targets)]
    return command + ["-e", "testedRevision", revision, RUNNER]


def tested_source():
    """identity() keeps the plain HEAD because run() sends it as composeRevision. The record marks a dirty tree."""
    tested = identity(apks=False)
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True, env=child_environment()))
    return {"revision": tested["revision"] + ("+dirty" if dirty else ""), "dirty": dirty, "sourceSha256": tested["sourceSha256"]}


def evidence_directory(args):
    directory = args.output / args.label
    if directory.exists():
        raise SystemExit(f"The evidence directory {directory} exists. Use a new label.")
    return directory


def write_run(directory, record):
    (directory / "run.json").write_text(json.dumps(record, indent=2) + "\n")
    print(f"Observed: {record['observed']}. Evidence: {directory}")
    return 0 if record["observed"] == record["expected"] else 1


def installed_apk_hash(adb, serial, package, commands, log):
    commands.append(adb_command(adb, "shell", "pm", "path", package, serial=serial))
    listed = subprocess.run(commands[-1], stdout=subprocess.PIPE, stderr=log, text=True, env=child_environment())
    if listed.returncode != 0:
        return None
    paths = [entry.removeprefix("package:") for entry in listed.stdout.split() if entry.startswith("package:")]
    if len(paths) != 1 or not re.fullmatch(r"/[A-Za-z0-9_./=~+-]+\.apk", paths[0]):
        return None
    commands.append(adb_command(adb, "shell", "sha256sum", paths[0], serial=serial))
    hashed = subprocess.run(commands[-1], stdout=subprocess.PIPE, stderr=log, text=True, env=child_environment())
    fields = hashed.stdout.split()
    return fields[0] if hashed.returncode == 0 and fields else None


def suite(args):
    """Build both APKs and run device tests on the selected emulator without clearing app data."""
    for target in args.targets:
        # adb joins its arguments into one device shell line, so a space or a $ would change the am command.
        if not re.fullmatch(r"[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?", target):
            raise SystemExit(f"The target {target!r} is not a class or method name. Use com.alpha.assistant.<package>.<Class>[#<method>].")
    started = time.monotonic()
    baseline = json.loads(args.baseline.read_text()) if args.baseline else {"failures": [], "skipped": []}
    known, known_skipped = set(baseline["failures"]), set(baseline["skipped"])
    directory = evidence_directory(args)
    state_command = adb_command(args.adb, "get-state", serial=args.serial)
    record = {**tested_source(), "apks": None, "installedApkSha256": None, "installedTestApkSha256": None,
              "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "serial": args.serial, "commands": [], "targets": args.targets, "expected": args.expect,
              "baseline": None if args.baseline is None else str(args.baseline)}
    directory.mkdir(parents=True)

    def device_command(*parts):
        return adb_command(args.adb, *parts, serial=args.serial)

    def read_device(command, log):
        record["commands"].append(command)
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=log, text=True, env=child_environment())
        return result.stdout.strip() if result.returncode == 0 else ""

    with (directory / "device.log").open("w") as log:
        online = read_device(state_command, log) == "device" and read_device(device_command("shell", "getprop", "ro.kernel.qemu"), log) == "1"
        api = read_device(device_command("shell", "getprop", "ro.build.version.sdk"), log) if online else ""
        fingerprint = read_device(device_command("shell", "getprop", "ro.build.fingerprint"), log) if online else ""
        webview = read_device(device_command("shell", "dumpsys", "webviewupdate"), log) if online else ""
    if not (online and re.fullmatch(r"[0-9]{2}", api) and int(api) >= 29 and fingerprint and webview):
        record.update(observed="failed", tests=0, failures=[], skipped=[], deviceError="Emulator identity unavailable.",
                      elapsedSeconds=round(time.monotonic() - started, 1))
        return write_run(directory, record)
    record["device"] = {"api": int(api), "fingerprint": fingerprint, "webView": webview}

    def execute(command, log, environment=None):
        record["commands"].append(command)
        return subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT,
                              env=child_environment() | (environment or {})).returncode == 0

    with (directory / "build.log").open("w") as log:
        built = execute(["./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain"], log, BUILD_ENVIRONMENT)
    outcome = {"observed": "build-failed", "tests": 0, "failures": [], "skipped": []}
    if built:
        record["apks"] = {path: digest(ROOT / path) for path in APKS}
        with (directory / "install.log").open("w") as log:
            prepared = [execute(device_command("install", "-r", path), log) for path in APKS]
            record["installedApkSha256"] = installed_apk_hash(args.adb, args.serial, "com.alpha.assistant", record["commands"], log)
            record["installedTestApkSha256"] = installed_apk_hash(args.adb, args.serial, "com.alpha.assistant.test", record["commands"], log)
            # A crashed earlier test can leave a large font or night mode.
            prepared += [execute(device_command("shell", "settings", "put", "system", "font_scale", "1.0"), log),
                         execute(device_command("shell", "cmd", "uimode", "night", "no"), log)]
        installed = [record["installedApkSha256"], record["installedTestApkSha256"]]
        if all(prepared) and installed == [record["apks"][path] for path in APKS]:
            with (directory / "stdout.log").open("w") as log:
                completed = execute(suite_instrument_command(args.adb, args.targets, record["revision"], serial=args.serial), log)
            outcome = suite_outcome((directory / "stdout.log").read_text(errors="replace"), known, known_skipped)
            if not completed:
                outcome["observed"] = "failed"
        else:
            outcome = {"observed": "failed", "tests": 0, "failures": [], "skipped": [],
                       "deviceError": "The selected emulator did not install both tested APKs."}
    record.update(outcome, elapsedSeconds=round(time.monotonic() - started, 1))
    return write_run(directory, record)


def host(args):
    started = time.monotonic()
    directory = evidence_directory(args)
    command = ["python3", "tools/compose_acceptance/test_harness.py"]
    record = {**tested_source(), "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "commands": [command], "expected": args.expect}
    directory.mkdir(parents=True)
    with (directory / "stdout.log").open("w") as log:
        exit_code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT, env=child_environment()).returncode
    record.update(observed="passed" if exit_code == 0 else "failed", elapsedSeconds=round(time.monotonic() - started, 1))
    return write_run(directory, record)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("matrix")
    execute = commands.add_parser("run")
    execute.add_argument("--scenario", type=int, choices=range(1, 7), required=True)
    execute.add_argument("--repetition", type=int, choices=range(1, 4), required=True)
    execute.add_argument("--live", action="store_true", help="Authorize one real generation with up to three PLAN and three COMPLETE repairs.")
    execute.add_argument("--credential-check", action="store_true", help="Transfer a synthetic sentinel without a provider call or artifact generation.")
    execute.add_argument("--seconds", type=int, choices=range(1, 181), default=30)
    execute.add_argument("--adb", default=ADB)
    execute.add_argument("--output", type=Path, default=ROOT / "evidence/compose-execution/scenarios")
    course = commands.add_parser("course", help="Capture one live lesson on a dedicated emulator.")
    course.add_argument("--serial", required=True)
    course.add_argument("--label", required=True)
    course.add_argument("--scenario", required=True, choices=("alphabet", "weather", "fractions", "moon-canvas"))
    course.add_argument("--baseline", action="store_true", help="Measure whether the first slide appears.")
    course.add_argument("--adb", default=ADB)
    course.add_argument("--output", type=Path, default=ROOT / "evidence/lesson-reliability")
    device = commands.add_parser("suite", help="Build, replace-install, and run device tests on the selected emulator.")
    device.add_argument("--label", required=True)
    device.add_argument("--serial", required=True, help="Exact emulator serial, such as emulator-5556.")
    device.add_argument("--expect", choices=("passed", "failed", "build-failed"), default="passed")
    device.add_argument("--baseline", type=Path, help="A run.json whose failures and skipped tests are known.")
    device.add_argument("--adb", default=ADB)
    device.add_argument("--output", type=Path, default=ROOT / "evidence/alphy-ui")
    device.add_argument("targets", nargs="*", help="com.alpha.assistant.<package>.<Class>[#<method>]. None runs the full suite.")
    host_tests = commands.add_parser("host", help="Run the host tests and record the evidence.")
    host_tests.add_argument("--label", required=True)
    host_tests.add_argument("--expect", choices=("passed", "failed"), default="passed")
    host_tests.add_argument("--output", type=Path, default=ROOT / "evidence/alphy-ui")
    check = commands.add_parser("verify")
    check.add_argument("directory", type=Path)
    failed = commands.add_parser("verify-failed", help="Validate failed evidence without asserting workflow success.")
    failed.add_argument("directory", type=Path)
    audit = commands.add_parser("audit")
    audit.add_argument("directory", type=Path, help="The real evidence directory, containing exactly 18 run directories.")
    args = parser.parse_args()
    if args.command == "matrix":
        print(json.dumps(matrix(scenarios())))
    elif args.command == "run":
        if args.live and args.credential_check:
            parser.error("The credential check cannot use a real provider.")
        run(args)
    elif args.command == "verify":
        if not verify(args.directory):
            raise SystemExit(1)
    elif args.command == "verify-failed":
        verify_failed(args.directory)
    elif args.command == "course":
        raise SystemExit(run_course(args))
    elif args.command == "suite":
        raise SystemExit(suite(args))
    elif args.command == "host":
        raise SystemExit(host(args))
    else:
        directories = sorted(path.parent for path in args.directory.glob("*/manifest.json"))
        if not all([verify(path) for path in directories]):
            raise SystemExit(1)
        rows = [json.loads((path / "report.json").read_text()) for path in directories]
        validate_matrix(rows)
        print("PASS: 18 independent real artifacts and complete interaction evidence.")


if __name__ == "__main__":
    main()
