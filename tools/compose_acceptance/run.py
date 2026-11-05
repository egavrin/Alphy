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


def adb_command(adb, *args):
    return [adb, "-s", "emulator-5556", *args]


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


def transfer_key(adb, run_id, key):
    payload = bytearray(key.encode("utf-8"))
    if not 1 <= len(payload) <= 8192 or not key.strip() or "\n" in key or "\r" in key:
        raise RuntimeError("DEEPSEEK_API_KEY has an invalid credential envelope.")
    port = None
    try:
        result = subprocess.run(adb_command(adb, "forward", "tcp:0", "localabstract:compose-credential-" + run_id),
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
            removed = subprocess.run(adb_command(adb, "forward", "--remove", "tcp:" + str(port)),
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
    paths = sorted(path for parent in ("app/src", "tools/compose_acceptance") for path in (ROOT / parent).rglob("*")
                   if path.is_file() and "__pycache__" not in path.parts)
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
    attempts = report.get("generationAttempts", [])
    if not isinstance(attempts, list) or not 1 <= len(attempts) <= 2:
        raise ValueError("Failed evidence needs one or two terminal attempt records.")
    outcomes = {"rejected", "providerFailure", "runtimeUnavailable", "canceled"}
    stages = {"availability", "provider", "definition", "state", "smoke"}
    for index, attempt in enumerate(attempts, 1):
        if (type(attempt.get("attempt")) is not int or attempt["attempt"] != index
                or attempt.get("outcome") not in outcomes or attempt.get("stage") not in stages
                or not attempt.get("failureCode") or not attempt.get("category")):
            raise ValueError("Failed attempt identity or terminal diagnostic is incomplete.")
    if len(attempts) == 2 and attempts[0]["outcome"] != "rejected":
        raise ValueError("Only rejected model output permits a correction.")
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
    """Classify raw `am instrument -r` output. Only a finished JUnit run without an unknown failure or skip passes."""
    status, failures, skipped = {}, set(), set()
    for line in stdout.splitlines():
        kind, _, value = line.strip().partition(": ")
        if kind == "INSTRUMENTATION_STATUS":
            key, _, value = value.partition("=")
            status[key] = value
        elif kind == "INSTRUMENTATION_STATUS_CODE":
            test = status.get("class", "") + "#" + status.get("test", "")
            if value in ("-1", "-2"):
                failures.add(test)
            elif value in ("-3", "-4"):
                skipped.add(test)
            status = {}
    counts = re.findall(r"^(?:OK \((\d+) tests?\)|Tests run: (\d+))", stdout, re.M)
    finished = bool(re.search(r"^INSTRUMENTATION_CODE: -1\s*$", stdout, re.M) and re.search(r"^(?:OK \(|FAILURES!!!)", stdout, re.M))
    # A class-level failure, such as one in @AfterClass, reaches the JUnit summary without a test block.
    unattributed = bool(re.search(r"^FAILURES!!!", stdout, re.M)) and not failures
    passed = finished and not unattributed and failures <= set(known) and skipped <= set(known_skipped)
    return {"observed": "passed" if passed else "failed", "tests": int("".join(counts[-1])) if counts else 0,
            "failures": sorted(failures), "skipped": sorted(skipped)}


def suite_instrument_command(adb, targets, revision):
    command = adb_command(adb, "shell", "am", "instrument", "-w", "-r")
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


def installed_apk_hash(adb, commands, log):
    commands.append(adb_command(adb, "shell", "pm", "path", "com.alpha.assistant"))
    listed = subprocess.run(commands[-1], stdout=subprocess.PIPE, stderr=log, text=True, env=child_environment()).stdout.split()
    paths = [entry.removeprefix("package:") for entry in listed if entry.startswith("package:")]
    if not paths:
        return None
    commands.append(adb_command(adb, "shell", "sha256sum", paths[0]))
    hashed = subprocess.run(commands[-1], stdout=subprocess.PIPE, stderr=log, text=True, env=child_environment()).stdout.split()
    return hashed[0] if hashed else None


def suite(args):
    """Build both APKs, replace-install them on emulator-5556, and run device tests. The app keeps its data and key."""
    for target in args.targets:
        # adb joins its arguments into one device shell line, so a space or a $ would change the am command.
        if not re.fullmatch(r"[A-Za-z0-9_.]+(#[A-Za-z0-9_]+)?", target):
            raise SystemExit(f"The target {target!r} is not a class or method name. Use com.alpha.assistant.<package>.<Class>[#<method>].")
    started = time.monotonic()
    baseline = json.loads(args.baseline.read_text()) if args.baseline else {"failures": [], "skipped": []}
    known, known_skipped = set(baseline["failures"]), set(baseline["skipped"])
    directory = evidence_directory(args)
    subprocess.run(adb_command(args.adb, "get-state"), check=True, capture_output=True, env=child_environment())
    record = {**tested_source(), "apks": None, "installedApkSha256": None, "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "serial": "emulator-5556", "commands": [], "targets": args.targets, "expected": args.expect,
              "baseline": None if args.baseline is None else str(args.baseline)}
    directory.mkdir(parents=True)

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
            prepared = [execute(adb_command(args.adb, "install", "-r", path), log) for path in APKS]
            record["installedApkSha256"] = installed_apk_hash(args.adb, record["commands"], log)
            # A crashed earlier test can leave a large font or night mode.
            prepared += [execute(adb_command(args.adb, "shell", "settings", "put", "system", "font_scale", "1.0"), log),
                         execute(adb_command(args.adb, "shell", "cmd", "uimode", "night", "no"), log)]
        with (directory / "stdout.log").open("w") as log:
            execute(suite_instrument_command(args.adb, args.targets, record["revision"]), log)
        outcome = suite_outcome((directory / "stdout.log").read_text(errors="replace"), known, known_skipped)
        if not all(prepared) or record["installedApkSha256"] != record["apks"][APKS[0]]:
            outcome["observed"] = "failed"
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
    execute.add_argument("--live", action="store_true", help="Authorize one real normal generation, with at most one correction.")
    execute.add_argument("--credential-check", action="store_true", help="Transfer a synthetic sentinel without a provider call or artifact generation.")
    execute.add_argument("--seconds", type=int, choices=range(1, 181), default=30)
    execute.add_argument("--adb", default=ADB)
    execute.add_argument("--output", type=Path, default=ROOT / "evidence/compose-execution/scenarios")
    device = commands.add_parser("suite", help="Build, replace-install, and run device tests on emulator-5556 only.")
    device.add_argument("--label", required=True)
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
