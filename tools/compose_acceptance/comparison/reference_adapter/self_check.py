#!/usr/bin/env python3
"""Check one reference host slot at a time with an isolated synthetic snapshot."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from compare import digest, encode, make_plan


# Failure cases: changed plan, unsafe TSV request, wrong source identity,
# output collision, correction without failure or beyond its limit, foreign
# result, caught error with exit zero, nonzero exit, and private output leaks.
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(mode=0o700)
    repo = out / "snapshot fixture"
    harness = repo / "tools/vera-harness/agent-run.js"
    harness.parent.mkdir(parents=True)
    harness.write_bytes((HERE / "agent-run.fixture.js").read_bytes())
    typescript = out / "typescript.fixture.js"
    typescript.write_text("module.exports = {}\n", encoding="utf-8")
    git_env = {"PATH": "/usr/bin:/bin", "HOME": str(out), "GIT_CONFIG_NOSYSTEM": "1",
               "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
               "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
               "GIT_AUTHOR_DATE": "2026-01-01T00:00:00+00:00", "GIT_COMMITTER_DATE": "2026-01-01T00:00:00+00:00"}

    def git(*cmd):
        return subprocess.check_output(["git", "-C", str(repo), *cmd], env=git_env, stderr=subprocess.PIPE).strip()

    git("init", "-q")
    git("add", ".")
    git("commit", "-qm", "Synthetic reference harness")
    revision = git("rev-parse", "HEAD").decode()
    common = ["--snapshot", str(repo), "--revision", revision, "--node", str(args.node),
              "--typescript", str(typescript)]
    env = {"PATH": "/usr/bin:/bin", "HOME": "/synthetic-parent-home", "NODE_OPTIONS": "--synthetic-invalid-flag",
           "PYTHONDONTWRITEBYTECODE": "1",
           "VERA_SOURCE_ROOT": "/synthetic-source", "AGENT_ANSWER": "other", "REFERENCE_FIXTURE_MARKER": "synthetic",
           **dict.fromkeys(["ARKESTR_API_KEY", "TYPESAFE_API_KEY", "EXA_API_KEY"], "synthetic-only")}
    observations = {}

    def adapter(name, command, *options, status=0):
        result = subprocess.run([sys.executable, str(HERE / "reference_capture.py"), command, *common, *options],
                                env=env, capture_output=True, check=False)
        assert result.returncode == status, (name, result.returncode, result.stderr)
        assert b"synthetic-text-do-not-retain" not in result.stdout + result.stderr
        observations[name] = {"expected_exit": status, "observed_exit": result.returncode}
        return json.loads(result.stdout) if result.stdout else None

    identity = adapter("identity", "identity")
    assert identity["revision"] == revision
    product = {**identity, "source_status": "synthetic fixture", "features": "synthetic fixture",
               "device": {"model": "host", "os": "synthetic", "config": "no native device"}}

    def slot(name, request, reference=product):
        case = {"id": f"extra:{name}", "request": request, "split": "supplemental", "interactions": {"tap": "Tap"}}
        plan = make_plan([case], {"products": {"candidate": product, "reference": reference}}, {}, [], "final")
        data = encode(plan)
        path = out / (name + ".plan.json")
        path.write_bytes(data)
        return ["--plan", str(path), "--plan-sha256", digest(data), "--slot", f"extra:{name}:reference:1",
                "--output", str(out / name)]

    request = '  Сделай "счётчик" $(synthetic-shell-marker)  '
    success = slot("success", request)
    bad_hash = [*success]
    bad_hash[3] = "0" * 64
    adapter("changed-plan", "capture", *bad_hash, status=2)
    adapter("unsafe-tsv", "capture", *slot("unsafe", "Synthetic\trequest"), status=2)
    adapter("wrong-source", "capture", *slot("wrong-source", request, {**product, "source_sha256": "0" * 64}), status=2)
    dry_run = adapter("dry-run", "capture", *success)
    assert dry_run["status"] == "dry-run" and not (out / "success").exists()
    result = adapter("exact-request", "capture", *success, "--mode", "synthetic")
    assert result["request_sha256"] == digest(request.encode("utf-8"))
    assert result["slot_id"] == "extra:success:reference:1" and result["plan_sha256"] == success[3]
    assert result["source"]["revision"] == revision and result["source"]["source_sha256"] == identity["source_sha256"]
    assert result["host_outcome"] == "built" and result["origin"] == "synthetic"
    assert result["artifacts"]["program.vera"]["sha256"] == digest(b"synthetic-text-do-not-retain")
    assert result["artifacts"]["holes.jsonl"]["bytes"] == 0
    assert all(value == "unavailable" for value in result["comparison_evidence"].values())
    retained_program = adapter("retain-program", "capture", *slot("retain", request), "--mode", "synthetic",
                               "--retain-program")
    kept = out / "retain/attempt-1/program.vera"
    assert digest(kept.read_bytes()) == retained_program["artifacts"]["program.vera"]["sha256"]
    assert kept.parent.stat().st_mode & 0o777 == 0o700
    assert retained_program["raw_artifacts_retained"] is True and result["raw_artifacts_retained"] is False
    retained = out / "success/attempt-1/result.json"
    assert json.loads(retained.read_bytes()) == result
    before = retained.read_bytes()
    adapter("collision", "capture", *success, "--mode", "synthetic", status=2)
    adapter("correction-without-failure", "capture", *success, "--mode", "synthetic", "--correction", status=2)
    assert retained.read_bytes() == before

    failure = slot("caught", "Synthetic caught failure")
    first = adapter("caught-failure", "capture", *failure, "--mode", "synthetic", status=1)
    assert first["exit_code"] == 0 and first["failure"] == "caught_prompt_error"
    assert first["host_outcome"] == "failed" and first["generation_id"] != result["generation_id"]
    assert first["artifacts"].keys() == {"result.json"}
    initial_file = out / "caught/attempt-1/result.json"
    initial_data = initial_file.read_bytes()
    correction = adapter("correction", "capture", *failure, "--mode", "synthetic", "--correction", status=1)
    assert correction["generation_id"] == first["generation_id"] and correction["attempt_id"] != first["attempt_id"]
    assert correction["previous_attempt_sha256"] == digest(initial_data) and correction["attempt"] == 2
    adapter("second-correction", "capture", *failure, "--mode", "synthetic", "--correction", status=2)
    assert initial_file.read_bytes() == initial_data

    wrong = adapter("foreign-result", "capture", *slot("foreign", "Synthetic bad result"), "--mode", "synthetic", status=1)
    assert wrong["failure"] == "result_identity_mismatch"
    nonzero = adapter("nonzero", "capture", *slot("nonzero", "Synthetic nonzero exit"), "--mode", "synthetic", status=1)
    assert nonzero["failure"] == "nonzero_exit" and nonzero["exit_code"] == 7 and "holes.jsonl" in nonzero["artifacts"]
    not_built = adapter("unclassified", "capture", *slot("not-built", "Synthetic not built"), "--mode", "synthetic")
    assert not_built["host_outcome"] == "not-built" and "outcome" not in not_built
    for directory in ("success", "caught", "foreign", "nonzero", "not-built"):
        for path in (out / directory).rglob("*"):
            if path.is_file():
                assert path.name in {"started.json", "result.json"}
                assert b"synthetic-text-do-not-retain" not in path.read_bytes()
    assert git("status", "--porcelain") == b""
    (out / "check.json").write_bytes(encode({"synthetic_revision": revision, "observations": observations,
                                              "adapter_sha256": digest((HERE / "reference_capture.py").read_bytes()),
                                              "result": "pass"}))
    print(f"PASS: {len(observations)} offline reference checks. Evidence: {out / 'check.json'}")


if __name__ == "__main__":
    main()
