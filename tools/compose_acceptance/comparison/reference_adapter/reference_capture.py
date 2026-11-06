#!/usr/bin/env python3
"""Capture host evidence for one frozen reference slot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from compare import digest, encode, hex_id

HARNESS = "tools/vera-harness/agent-run.js"
ROUTE = "vera-reference-host"
ARGV = [HARNESS, "-r", "-j", "1", "-p", "../request.tsv", "-o", "../raw", "slot"]
ARTIFACTS = ("result.json", "program.vera", "holes.jsonl")
UNAVAILABLE = dict.fromkeys(("first_ui_ms", "completed_ui_ms", "frame_p95_us", "change_ms", "native_rendering",
                            "interactions", "visual", "provider_calls", "route_stage_timings", "parts_only", "recovery"),
                           "unavailable")
GIT_ENV = {"PATH": "/usr/bin:/bin", "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1",
           "GIT_NO_REPLACE_OBJECTS": "1", "GIT_NO_LAZY_FETCH": "1", "GIT_ALLOW_PROTOCOL": "",
           "GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"}


class InputError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InputError(message)


def file_sha256(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def source_archive(args, archive):
    require(hex_id(args.revision, 40), "Pin the full reference commit.")
    git = ["git", "-C", str(args.snapshot), "-c", "tar.umask=0022"]
    result = subprocess.run([*git, "cat-file", "-t", args.revision], env=GIT_ENV,
                            stdin=subprocess.DEVNULL, capture_output=True, check=False)
    require(result.returncode == 0 and result.stdout == b"commit\n", "The pinned reference commit is unavailable.")
    with archive.open("wb") as stream:
        result = subprocess.run([*git, "archive", "--format=tar", args.revision], env=GIT_ENV,
                                stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.DEVNULL, check=False)
    require(result.returncode == 0, "Cannot archive the pinned reference commit.")
    return file_sha256(archive)


def snapshot_harness(archive, source=None):
    with tarfile.open(archive, "r:") as bundle:
        members = bundle.getmembers()
        require(all((item.isfile() or item.isdir()) and not Path(item.name).is_absolute()
                    and ".." not in Path(item.name).parts for item in members),
                "The reference archive has an unsupported entry.")
        script = bundle.extractfile(HARNESS)
        require(script is not None, "The archive has no reference host harness.")
        with script:
            harness = script.read()
        if source is not None:
            bundle.extractall(source, members=members)
    return harness


def identity(args, archive, typescript):
    require(args.node.is_file() and os.access(args.node, os.X_OK), "Use an executable Node path.")
    runtime = {"node_sha256": file_sha256(args.node), "typescript_sha256": digest(typescript)}
    provider = {"name": "DeepSeek + Jev", "model": "deepseek-flash",
                "config": "api.deepseek.com; temperature=0; stream=false; api.typesafe.ai/v1/systemone; "
                          "default clarification; Exa " + ("enabled" if args.exa else "disabled")}
    return {"revision": args.revision, "source_sha256": source_archive(args, archive),
            "build_sha256": digest(encode(runtime)), "route": ROUTE, "provider": provider}, runtime


def load_slot(args):
    data = args.plan.read_bytes()
    require(digest(data) == args.plan_sha256, "The frozen plan hash does not match.")
    plan = json.loads(data)
    require(plan["format"] == 1 and plan["purpose"] in {"final", "tuning"}, "Unsupported frozen plan.")
    slots = [slot for slot in plan["slots"] if slot["id"] == args.slot]
    require(len(slots) == 1, "Select one frozen slot.")
    slot = slots[0]
    repeat = slot["repeat"]
    require(slot["product"] == "reference" and type(repeat) is int and 1 <= repeat <= 3 and
            args.slot == f"{slot['case_id']}:reference:{repeat}" and re.fullmatch(r"[A-Za-z0-9:_-]{1,180}", args.slot),
            "Select one reference slot.")
    cases = [case for case in plan["cases"] if case["id"] == slot["case_id"]]
    require(len(cases) == 1, "Select one frozen request.")
    case = cases[0]
    require(plan["purpose"] == "final" or case["split"] != "held-out", "Held-out slots require the final plan.")
    request = case["request"]
    require(isinstance(request, str) and request.strip() and not any(char in request for char in "\t\n\r\0"),
            "The exact frozen request does not fit the reference TSV format.")
    require(digest(request.encode("utf-8")) == slot["request_sha256"], "The frozen request hash does not match.")
    product = plan["execution"]["products"]["reference"]
    require(digest(encode(product)) == slot["execution_sha256"], "The frozen execution hash does not match.")
    return slot, case, product


def child_environment(args, scratch):
    home = scratch / "home"
    home.mkdir()
    env = {"HOME": str(home), "TMPDIR": str(scratch), "VERA_TYPESCRIPT_PATH": str(scratch / "typescript.js")}
    if args.mode == "live":
        keys = ["ARKESTR_API_KEY", "TYPESAFE_API_KEY"] + (["EXA_API_KEY"] if args.exa else [])
        for name in keys:
            value = os.environ.get(name)
            require(value and value.strip(), f"The required environment variable is absent: {name}.")
            env[name] = value
    return env


def read_host_result(raw, request, origin):
    artifacts = {}
    slot_dir = raw / "slot"
    if not raw.is_symlink() and not slot_dir.is_symlink():
        for name in ARTIFACTS:
            path = slot_dir / name
            if not path.is_symlink() and path.is_file():
                artifacts[name] = {"sha256": file_sha256(path), "bytes": path.stat().st_size, "origin": origin}
    if "result.json" not in artifacts:
        return {}, artifacts, "missing_result"
    try:
        result = json.loads((slot_dir / "result.json").read_bytes())
    except (ValueError, OSError):
        return {}, artifacts, "malformed_result"
    if not isinstance(result, dict):
        return {}, artifacts, "malformed_result"
    if result.get("label") != "slot" or result.get("prompt") != request:
        return {}, artifacts, "result_identity_mismatch"
    if "error" in result:
        return {}, artifacts, "caught_prompt_error"
    route, served, recorded = (result.get(key) for key in ("route", "served", "recorded"))
    if type(served) is not bool or type(recorded) is not bool or result.get("widget") is not False:
        return {}, artifacts, "malformed_result"
    if route not in ("agent", "not-built", "cancelled") or (route != "cancelled" and served != (route == "agent")):
        return {}, artifacts, "unsupported_host_result"
    observation = {"route": route, "served": served, "recorded": recorded}
    return observation, artifacts, "host_cancelled" if route == "cancelled" else None


def attempt_directory(output, correction, identity_fields):
    if not correction:
        output.mkdir(mode=0o700)
        directory = output / "attempt-1"
        lineage = {"attempt": 1, "generation_id": uuid.uuid4().hex}
    else:
        directory = output / "attempt-2"
        previous = output / "attempt-1/result.json"
        require(not output.is_symlink() and not previous.parent.is_symlink() and not previous.is_symlink(),
                "The previous attempt must be in this output directory.")
        data = previous.read_bytes()
        attempt = json.loads(data)
        require(attempt["attempt"] == 1 and attempt["status"] == "complete" and attempt["host_outcome"] == "failed" and
                hex_id(attempt["generation_id"], 32) and all(attempt[key] == value for key, value in identity_fields.items()),
                "A correction requires a failed attempt with the same frozen identity.")
        lineage = {"attempt": 2, "generation_id": attempt["generation_id"], "previous_attempt_sha256": digest(data)}
    directory.mkdir(mode=0o700)
    return directory, {**lineage, "attempt_id": uuid.uuid4().hex}


def capture(args, scratch, archive, source, runtime, typescript):
    slot, case, product = load_slot(args)
    require(all(product[key] == value for key, value in source.items()), "The reference host identity does not match.")
    require(1 <= args.timeout <= 1800, "Use a timeout of 1 to 1800 seconds.")
    fields = {"format": 1, "slot_id": slot["id"], "plan_sha256": args.plan_sha256,
              "request_sha256": slot["request_sha256"], "execution_sha256": slot["execution_sha256"],
              "source": source, "runtime": runtime, "mode": args.mode}
    packet = {**fields, "argv": ["node", *ARGV], "comparison_evidence": UNAVAILABLE,
              "state": "private scratch per attempt; native state unavailable", "raw_artifacts_retained": False}
    output = args.output.absolute()
    if not args.correction:
        require(not output.exists() and not output.is_symlink(), "The output directory already exists.")
    if args.mode == "dry-run":
        require(not args.correction, "A dry run cannot start a correction.")
        snapshot_harness(archive)
        return {**packet, "status": "dry-run"}, 0

    env = child_environment(args, scratch)
    source_dir = scratch / "source"
    harness = snapshot_harness(archive, source_dir)
    if args.mode == "synthetic":
        require(harness == (HERE / "agent-run.fixture.js").read_bytes(), "Synthetic mode requires the adapter fixture.")
    raw = scratch / "raw"
    raw.mkdir()
    (scratch / "typescript.js").write_bytes(typescript)
    (scratch / "request.tsv").write_bytes(f"slot\t{case['request']}\n".encode("utf-8"))
    directory, lineage = attempt_directory(output, args.correction, fields)
    origin = "synthetic" if args.mode == "synthetic" else "reference-host"
    packet.update(lineage, origin=origin, timeout_seconds=args.timeout)
    (directory / "started.json").write_bytes(encode({**packet, "status": "started"}))

    exit_code, failure = None, None
    try:
        result = subprocess.run([str(args.node.absolute()), *ARGV], cwd=source_dir, env=env, timeout=args.timeout,
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        exit_code = result.returncode
        if exit_code != 0:
            failure = "nonzero_exit"
    except subprocess.TimeoutExpired:
        failure = "timeout"
    except OSError:
        failure = "launch_failed"
    try:
        observation, artifacts, result_failure = read_host_result(raw, case["request"], origin)
    except OSError:
        observation, artifacts, result_failure = {}, {}, "artifact_read_failed"
    failure = failure or result_failure
    outcome = "failed" if failure else ("built" if observation["served"] else "not-built")
    if args.retain_program and outcome == "built":
        try:
            program = (raw / "slot" / "program.vera").read_bytes()
            require(program and digest(program) == artifacts["program.vera"]["sha256"], "Program identity differs.")
            (directory / "program.vera").write_bytes(program)
            packet["raw_artifacts_retained"] = True
        except (KeyError, OSError, ValueError):
            outcome, failure = "failed", "program_retention_failed"
    packet.update(status="complete", exit_code=exit_code, failure=failure, host_outcome=outcome,
                  host_observation=observation, artifacts=artifacts)
    (directory / "result.json").write_bytes(encode(packet))
    return packet, int(failure is not None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    show = commands.add_parser("identity", help="Print the host identity without starting the harness.")
    run = commands.add_parser("capture", help="Validate or run one frozen reference host slot.")
    for command in (show, run):
        command.add_argument("--snapshot", type=Path, required=True)
        command.add_argument("--revision", required=True)
        command.add_argument("--node", type=Path, required=True)
        command.add_argument("--typescript", type=Path, required=True)
        command.add_argument("--exa", action="store_true")
    run.add_argument("--plan", type=Path, required=True)
    run.add_argument("--plan-sha256", required=True)
    run.add_argument("--slot", required=True)
    run.add_argument("--output", type=Path, required=True)
    run.add_argument("--mode", choices=("dry-run", "synthetic", "live"), default="dry-run")
    run.add_argument("--retain-program", action="store_true",
                     help="Keep the generated VERA source in the private attempt directory for functional review.")
    run.add_argument("--correction", action="store_true")
    run.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    try:
        with tempfile.TemporaryDirectory(prefix="reference-host-", dir="/tmp") as temp:
            scratch = Path(temp)
            archive = scratch / "source.tar"
            require(args.typescript.is_file(), "Use a TypeScript module entry file.")
            typescript = args.typescript.read_bytes()
            source, runtime = identity(args, archive, typescript)
            if args.command == "identity":
                snapshot_harness(archive)
                packet, status = source, 0
            else:
                packet, status = capture(args, scratch, archive, source, runtime, typescript)
        print(encode(packet).decode("utf-8"), end="")
        return status
    except InputError as error:
        print(str(error), file=sys.stderr)
        return 2
    except (KeyError, TypeError, ValueError, OSError, tarfile.TarError):
        print("Reference host input, identity, or output check failed.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
