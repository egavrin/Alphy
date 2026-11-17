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
# Every SemanticIndex is one AppSearch database. Its SemanticChunk schema type survives in the shared store (SemanticIndex.kt).
CHUNK_PROPERTIES = {"source", "revision", "signature", "start", "end", "vector"}
# On 2026-11-17 the store refused new indexes with 1671 leaked types ("No more space left"); a cleared device holds 0.
# 1000 leaves room for the slow leak of crashed runs, and it stops a run well before the store is full.
SEMANTIC_CHUNK_LIMIT = 1000
# The AiProvider ids of the app and the key variable of each (spec 15.2).
PROVIDER_KEYS = {"openai": "OPENAI_API_KEY", "anthropic": "ANTHROPIC_API_KEY",
                 "openrouter": "OPENROUTER_API_KEY", "deepseek": "DEEPSEEK_API_KEY"}
# A live test of the app that live-check may start: one method of a class whose name starts with Live.
LIVE_TEST = r"com\.alpha\.assistant(?:\.[a-z][a-z0-9]*)*\.Live[A-Za-z0-9]*#[A-Za-z0-9_]+"


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
    return {key: value for key, value in os.environ.items() if key not in PROVIDER_KEYS.values()}


def semantic_chunk_count(dump):
    """The number of SemanticChunk schema types in a dumpsys app_search text, or None when the text has no schema."""
    if "schema {" not in dump:
        return None
    count, names = 0, None
    for line in dump.splitlines():
        if line == "  types {":
            names = set()
        elif names is not None and line == "  }":
            if names == CHUNK_PROPERTIES:
                count += 1
            names = None
        elif names is not None and line.startswith('      property_name: "'):
            names.add(line.split('"')[1])
    return count


def semantic_store_error(count):
    """An environment error text only when a count above the limit was read. An unknown count (None) never stops a run."""
    if count is not None and count > SEMANTIC_CHUNK_LIMIT:
        return (f"The AppSearch store holds {count} leaked SemanticChunk types (limit {SEMANTIC_CHUNK_LIMIT}). "
                "New indexes can fail with 'No more space left', so tests fail on correct code. "
                "Clear it with the approved 'adb -s <serial> shell pm clear com.alpha.assistant'.")
    return None


def semantic_unknown_note(count):
    """One warning line and a short reason for run.json when the count could not be read, else None."""
    if count is not None:
        return None
    note = "dumpsys app_search gave no readable SemanticChunk count"
    print(f"Warning: {note}; the run continues without the store guard.")
    return note


def dotenv_value(name, environment=None):
    """The nonempty value of [name] from the process environment, else from ~/.env read as data, else None.
    Error texts name the variable, never a value."""
    environment = os.environ if environment is None else environment
    if environment.get(name):
        return environment[name]
    try:
        lines = (Path.home() / ".env").read_text().splitlines()
    except FileNotFoundError:
        lines = []
    except OSError:
        raise RuntimeError(f"Cannot read {name} from ~/.env.") from None
    for line in lines:
        match = re.match(rf"^\s*(?:export\s+)?{name}\s*=\s*(.*)$", line)
        if match:
            try:
                values = shlex.split(match.group(1), comments=True)
            except ValueError:
                raise RuntimeError(f"Cannot parse the {name} dotenv entry.") from None
            if len(values) == 1 and values[0]:
                return values[0]
    return None


def selected_provider(environment=None, default=None):
    provider = dotenv_value("ALPHY_AI_PROVIDER", environment) or default
    if provider not in PROVIDER_KEYS:
        raise RuntimeError("Set ALPHY_AI_PROVIDER to openai, anthropic, openrouter, or deepseek.")
    return provider


def read_live_account(environment=None):
    """The (provider id, key) pair of a live run. The provider is checked before its key variable, the only one read."""
    provider = selected_provider(environment)
    variable = PROVIDER_KEYS[provider]
    key = dotenv_value(variable, environment)
    if key is None:
        raise RuntimeError(f"{variable} is absent from the process environment and ~/.env.")
    return provider, key


def selected_model(environment=None):
    """ALPHY_AI_MODEL, or None for the first curated chat model. The device checks it against the provider's list."""
    model = dotenv_value("ALPHY_AI_MODEL", environment)
    if model is not None and not re.fullmatch(r"[A-Za-z0-9._/:-]{1,100}", model):
        raise RuntimeError("ALPHY_AI_MODEL must have 1 to 100 letters, digits, or the characters - . _ / :.")
    return model


def transfer_key(adb, run_id, account, *, serial="emulator-5556"):
    """Sends the (provider id, key) account as `<provider id>\\n<key>` through the one-use ADB peer."""
    provider, key = account
    if provider not in PROVIDER_KEYS or not re.fullmatch(r"[!-~]+", key) or len(provider) + 1 + len(key) > 8192:
        raise RuntimeError("The AI account has an invalid credential envelope.")
    payload = bytearray(f"{provider}\n{key}".encode("ascii"))
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


def instrument_command(adb, scenario, repetition, revision, source_hash, live, seconds, run_id="test", credential_probe=False, *, serial="emulator-5556", model=None):
    args = {"class": "com.alpha.assistant.tools.ComposeScenarioAcceptanceTest#generateAndPresentIndependentScenario",
            "composeLive": str(live).lower(), "composeScenario": str(scenario), "composeRepetition": str(repetition),
            "composeRevision": revision, "composeSourceSha256": source_hash, "composeRunId": run_id,
            "composeReviewSeconds": str(seconds), "composeCredentialProbe": str(credential_probe).lower()}
    if live or credential_probe:
        args["composeCredentialTransport"] = "adb"
    if model:
        args["aiModel"] = model
    command = adb_command(adb, "shell", "am", "instrument", "-w", "-r", serial=serial)
    for key, value in args.items():
        command += ["-e", key, value]
    return command + [RUNNER]


def live_test(name):
    return re.fullmatch(LIVE_TEST, name) is not None


def run_course(args):
    inputs = json.loads((ROOT / "app/src/androidTest/assets/course-scenarios.json").read_text())
    run_id = "course-" + uuid.uuid4().hex[:12]
    manifest = {"scenario": args.scenario, "input": inputs[args.scenario], "baseline": args.baseline}
    return live_instrumentation(args, run_id, "course-acceptance", manifest, [
        "-e", "class", "com.alpha.assistant.courses.LiveCourseWorkflowTest#generateAndPlay",
        "-e", "courseRunId", run_id, "-e", "courseScenario", args.scenario, "-e", "courseBaseline", str(args.baseline).lower()])


def live_check(args):
    """One Live* device test that takes `liveRunId` and writes `live-check/<run id>/report.json` with its phase."""
    if not live_test(args.test):
        raise SystemExit("Use one test method under com.alpha.assistant whose class name starts with Live.")
    run_id = "live-" + uuid.uuid4().hex[:12]
    return live_instrumentation(args, run_id, "live-check", {"test": args.test},
                                ["-e", "class", args.test, "-e", "liveRunId", run_id])


def live_instrumentation(args, run_id, evidence, manifest, extras):
    """Runs one live device test with the selected AI account on the emulator that holds the tested APKs.
    The account goes once through the one-use peer at the credentialReady phase. Passing needs exit 0,
    the phase complete with the provider that was sent, and OK (1 test), so a skipped test fails."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", args.label):
        raise SystemExit("Use an evidence label with letters, numbers, dots, dashes, or underscores.")
    directory = args.output / args.label / run_id
    directory.mkdir(parents=True, exist_ok=False)
    manifest.update(serial=args.serial, status="failed")
    account = process = None
    remote = f"/sdcard/Android/data/com.alpha.assistant/files/{evidence}/{run_id}"

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
        account = read_live_account()
        provider = account[0]
        model = selected_model()
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
        command = device("shell", "am", "instrument", "-w", "-r", *extras, *(["-e", "aiModel", model] if model else []), RUNNER)
        manifest["command"] = command
        with (directory / "instrumentation.log").open("w") as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=child_environment())
            deadline = time.monotonic() + 1500
            while process.poll() is None and time.monotonic() < deadline:
                if account is not None:
                    probe = subprocess.run(device("shell", "cat", remote + "/report.json"), capture_output=True,
                                           text=True, timeout=20, env=child_environment())
                    if probe.returncode == 0 and json.loads(probe.stdout).get("phase") == "credentialReady":
                        transfer_key(args.adb, run_id, account, serial=args.serial)
                        account = None
                time.sleep(0.25 if account is not None else 1)
            if process.poll() is None:
                raise RuntimeError("The live workflow timed out.")
        read_device("pull", remote + "/.", str(directory))
        report = json.loads((directory / "report.json").read_text())
        manifest.update(provider=report.get("provider"), model=report.get("model"))
        instrumentation = (directory / "instrumentation.log").read_text()
        if (process.returncode == 0 and report["phase"] == "complete" and report.get("provider") == provider
                and "OK (1 test)" in instrumentation):
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
    if args.credential_check:
        account = (selected_provider(default="deepseek"), "synthetic-credential-" + uuid.uuid4().hex)
    else:
        account = read_live_account() if args.live else None
    provider = account[0] if account else None
    model = selected_model() if account else None
    probe_secret = account[1] if args.credential_check else None
    run_id = f"s{args.scenario}-r{args.repetition}-{uuid.uuid4().hex[:12]}"
    directory = args.output / mode / run_id
    dump = subprocess.run(adb_command(args.adb, "shell", "dumpsys", "app_search", serial=args.serial), capture_output=True, text=True, env=child_environment())
    chunks = semantic_chunk_count(dump.stdout) if dump.returncode == 0 else None
    store_error = semantic_store_error(chunks)
    if store_error:
        raise SystemExit(store_error)
    unknown = semantic_unknown_note(chunks)
    directory.mkdir(parents=True, exist_ok=False)
    tested = identity()
    (directory / "identity.json").write_text(json.dumps(tested, indent=2) + "\n")
    remote = f"/sdcard/Android/data/com.alpha.assistant/files/compose-acceptance/{mode}/{run_id}"
    command = instrument_command(args.adb, args.scenario, args.repetition, tested["revision"], tested["sourceSha256"], args.live, args.seconds, run_id, args.credential_check, serial=args.serial, model=model)
    manifest = {"runId": run_id, "mode": mode, "scenario": args.scenario, "repetition": args.repetition,
                "request": case["request"], "command": command, "serial": args.serial, "semanticChunkTypes": chunks, "semanticChunkNote": unknown,
                "expected": "A normal independent generation, native render, and explicit interaction evidence.",
                "observed": "PENDING", "checks": [{"id": key, "status": "UNAVAILABLE", "expected": value,
                "observed": "Not reviewed", "evidence": ["report.json"]} for key, value in case["checks"].items()]}
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    subprocess.run(adb_command(args.adb, "get-state", serial=args.serial), check=True, capture_output=True, env=child_environment())
    recorder = None
    transfer_failed = False
    with (directory / "instrumentation.log").open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT, env=child_environment())
        deadline = time.monotonic() + 600 + args.seconds
        while process.poll() is None and time.monotonic() < deadline:
            probe = subprocess.run(adb_command(args.adb, "shell", "cat", remote + "/report.json", serial=args.serial), capture_output=True, text=True, env=child_environment())
            if recorder is None and probe.returncode == 0:
                report = json.loads(probe.stdout)
                if report.get("phase") == "credentialReady" and account is not None:
                    try:
                        transfer_key(args.adb, run_id, account, serial=args.serial)
                    except RuntimeError:
                        transfer_failed = True
                        manifest["credentialTransfer"] = "failed"
                    finally:
                        account = None
                if report.get("phase") == "review":
                    recorder = subprocess.Popen(adb_command(args.adb, "shell", "screenrecord", "--time-limit", str(min(args.seconds, 180)), remote + "/motion.mp4", serial=args.serial), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=child_environment())
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
    pulled = subprocess.run(adb_command(args.adb, "pull", remote + "/.", str(directory), serial=args.serial), capture_output=True, text=True, env=child_environment())
    (directory / "capture.log").write_text(pulled.stdout + pulled.stderr)
    report_path = directory / "report.json"
    report = json.loads(report_path.read_text()) if report_path.exists() else {}
    manifest.update(provider=report.get("provider"), model=report.get("model"))
    manifest["artifacts"] = {path.name: digest(path) for path in directory.iterdir() if path.is_file() and path.name != "manifest.json"}
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    if probe_secret is not None and any(probe_secret.encode() in path.read_bytes() for path in directory.iterdir() if path.is_file()):
        raise RuntimeError("The synthetic credential appeared in captured evidence.")
    print(directory)
    if transfer_failed or report.get("phase") != "complete" or report.get("provider") != provider:
        raise SystemExit("Generation or capture failed, or the device used another provider. Evidence remains in the run directory.")


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
        chunks = semantic_chunk_count(read_device(device_command("shell", "dumpsys", "app_search"), log)) if online else None
    if not (online and re.fullmatch(r"[0-9]{2}", api) and int(api) >= 29 and fingerprint and webview):
        record.update(observed="failed", tests=0, failures=[], skipped=[], deviceError="Emulator identity unavailable.",
                      elapsedSeconds=round(time.monotonic() - started, 1))
        return write_run(directory, record)
    record["device"] = {"api": int(api), "fingerprint": fingerprint, "webView": webview}
    record["semanticChunkTypes"] = chunks
    record["semanticChunkNote"] = semantic_unknown_note(chunks)
    store_error = semantic_store_error(chunks)
    if store_error:
        record.update(observed="environment-error", tests=0, failures=[], skipped=[], deviceError=store_error,
                      elapsedSeconds=round(time.monotonic() - started, 1))
        return write_run(directory, record)

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
    execute.add_argument("--serial", required=True, help="Exact emulator serial, such as emulator-5556.")
    execute.add_argument("--scenario", type=int, choices=range(1, 7), required=True)
    execute.add_argument("--repetition", type=int, choices=range(1, 4), required=True)
    execute.add_argument("--live", action="store_true", help="Authorize one real generation with the app agent loop.")
    execute.add_argument("--credential-check", action="store_true", help="Transfer a synthetic sentinel without a provider call or artifact generation.")
    execute.add_argument("--seconds", type=int, choices=range(1, 181), default=30)
    execute.add_argument("--adb", default=ADB)
    execute.add_argument("--output", type=Path, default=ROOT / "evidence/compose-execution/scenarios")
    course = commands.add_parser("course", help="Capture one live lesson on a dedicated emulator.")
    course.add_argument("--serial", required=True)
    course.add_argument("--label", required=True)
    course.add_argument("--scenario", required=True, choices=("alphabet", "weather", "fractions", "moon-canvas", "rectangle-area", "spanish-greetings"))
    course.add_argument("--baseline", action="store_true", help="Measure whether the first slide appears.")
    course.add_argument("--adb", default=ADB)
    course.add_argument("--output", type=Path, default=ROOT / "evidence/lesson-reliability")
    checked = commands.add_parser("live-check", help="Run one live device test with the selected AI account.")
    checked.add_argument("--serial", required=True)
    checked.add_argument("--label", required=True)
    checked.add_argument("--test", required=True, help="com.alpha.assistant.<package>.Live<Name>#<method>")
    checked.add_argument("--adb", default=ADB)
    checked.add_argument("--output", type=Path, default=ROOT / "evidence/live-checks")
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
    elif args.command == "live-check":
        raise SystemExit(live_check(args))
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
