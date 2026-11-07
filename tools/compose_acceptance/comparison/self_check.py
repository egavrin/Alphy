#!/usr/bin/env python3
"""Exercise the offline comparison CLI against the approved corpus."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unicodedata


CORPUS = "7ee06af1941729b00a110493ad9d1d9b5b127d17"
CLI = Path(__file__).with_name("compare.py")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def run(*args, expected=0):
    result = subprocess.run([sys.executable, str(CLI), *args], capture_output=True, text=True)
    assert result.returncode == expected, (args, result.returncode, result.stdout, result.stderr)
    return json.loads(result.stdout) if result.stdout else result.stderr


def main(reference):
    # Failure cases: lost requests, held-out leaks, false pass, replacement, wrong
    # identity, correction assigned to a new generation, and synthetic evidence.
    with tempfile.TemporaryDirectory(prefix="alphy-comparison-check-") as directory:
        root = Path(directory)
        inputs = root / "inputs.json"
        inputs.write_text(json.dumps({
            "approved_capabilities": ["calendar.read"],
            "interactions": {},
            "cases": [{
                "id": "calendar", "request": "Покажи события календаря", "language": "ru",
                "category": "calendar", "capabilities": ["calendar.read"],
                "interactions": {"read": "Show the seeded calendar event."},
                "expected_outcome": "generated",
            }],
        }))
        execution = root / "execution.json"
        identity = {
            "source_sha256": "2" * 64, "build_sha256": "3" * 64,
            "route": "fixture", "source_status": "offline fixture", "features": "offline fixture",
            "provider": {"name": "fixture", "model": "fixture", "config": "off"},
            "device": {"model": "fixture", "os": "fixture", "config": "off",
                       "fingerprint": "fixture", "size": "fixture", "density": "fixture"},
        }
        apks = {"com.alpha.assistant": "4" * 64, "com.alpha.assistant.test": "5" * 64}
        runtime = {"node_sha256": "6" * 64, "typescript_sha256": "7" * 64}
        candidate = {**identity, "revision": "1" * 40, "route": "alphy-android-chat",
                     "build_sha256": digest(encoded(apks))}
        reference_host = {**identity, "revision": CORPUS, "route": "vera-reference-host",
                          "build_sha256": digest(encoded(runtime))}
        execution.write_text(json.dumps({
            "products": {"candidate": candidate, "reference": reference_host},
            "reference_observation": {
                "remote": "https://example.invalid/reference.git", "ref": "refs/heads/main",
                "local_revision": CORPUS, "remote_revision": CORPUS, "checked_at": "2026-11-05T00:00:00Z",
                "local_source_status": "offline fixture", "reference_changes": "offline fixture",
            },
            "performance": {"latency": "incomparable", "frames": "incomparable", "reason": "Offline fixture."},
            "held_out_used_for_tuning": False,
        }))
        output = root / "plan"
        prepared = run("prepare", "--reference-repo", reference, "--corpus-revision", CORPUS,
                       "--inputs", str(inputs), "--execution", str(execution), "--out", str(output))
        assert prepared["english_requests"] == 121 and prepared["held_out_requests"] == 45
        assert prepared["english_slots"] == 726 and prepared["slots"] == 732
        final_path = output / "final.json"
        final = json.loads(final_path.read_text())
        tuning = json.loads((output / "tuning.json").read_text())
        all_requests = {unicodedata.normalize("NFKC", " ".join(case["request"].split())).casefold()
                        for case in final["cases"]}
        assert len(all_requests) == 122
        held_out = {case["id"] for case in final["cases"] if case["split"] == "held-out"}
        assert len(held_out) == 45 and len(tuning["cases"]) == 77
        assert held_out.isdisjoint({case["id"] for case in tuning["cases"]})
        assert all('"request": ' + json.dumps(case["request"], ensure_ascii=False)
                   not in (output / "tuning.json").read_text()
                   for case in final["cases"] if case["id"] in held_out)
        assert len({slot["id"] for slot in final["slots"]}) == 732
        assert sum(slot["case_id"] in held_out for slot in final["slots"]) == 270
        assert all(len(case["source"]["sha256"]) == 64 for case in final["cases"])
        with_corpus_checks = json.loads(inputs.read_text())
        with_corpus_checks["use_corpus_checks"] = True
        inputs.write_text(json.dumps(with_corpus_checks))
        checked = root / "with-corpus-checks"
        run("prepare", "--reference-repo", reference, "--corpus-revision", CORPUS,
            "--inputs", str(inputs), "--execution", str(execution), "--out", str(checked))
        checks = json.loads((checked / "tuning.json").read_text())["cases"]
        by_id = {case["id"]: case for case in checks}
        assert by_id["fresh:unit-1"]["interactions"]["corpus_result"] == "10 km -> 6.21 miles"
        assert by_id["fresh:outside-1"]["expected_outcome"] == "either"
        assert by_id["fresh:beyond-5"]["game_rules"] == ["game_rules"]
        duplicate = json.loads(inputs.read_text())
        duplicate["cases"][0]["request"] = "  POUNDS to   kilograms converter  "
        inputs.write_text(json.dumps(duplicate))
        error = run("prepare", "--reference-repo", reference, "--corpus-revision", CORPUS,
                    "--inputs", str(inputs), "--execution", str(execution), "--out", str(root / "duplicate"),
                    expected=2)
        assert "Duplicate normalized request" in error

        results = root / "records.jsonl"
        plan_hash = digest(final_path.read_bytes())
        command = ("audit", "--plan", str(final_path), "--plan-sha256", plan_hash,
                   "--results", str(results), "--evidence-dir", str(root))
        results.touch()
        report = run(*command, expected=1)
        assert report["gate"] == "blocked" and report["missing_slots"] == 732
        assert report["splits"]["held-out"]["missing"] == 270
        assert report["execution"]["products"]["candidate"]["revision"] == "1" * 40

        evidence = root / "fixture.log"
        evidence.write_text("Synthetic failure fixture. No provider or device was used.")
        record = {
            "slot_id": final["slots"][0]["id"], "plan_sha256": plan_hash,
            "execution_sha256": final["slots"][0]["execution_sha256"],
            "request_sha256": final["slots"][0]["request_sha256"], "mode": "synthetic",
            "generation_id": "fixture-generation",
            "fresh_state": {"draft": "draft-a", "session": "session-a", "store": "store-a"},
            "outcome": "failed", "failure": "fixture only",
            "attempts": [
                {"number": 1, "generation_id": "fixture-generation", "outcome": "failed",
                 "provider_calls": 0, "stages": {"fixture": 1}},
                {"number": 2, "generation_id": "fixture-generation", "outcome": "failed",
                 "provider_calls": 0, "stages": {"fixture": 1}},
            ],
            "evidence": {"generation": {"path": "fixture.log", "sha256": digest(evidence.read_bytes()),
                                        "origin": "synthetic"}},
        }
        results.write_text(json.dumps(record) + "\n")
        report = run(*command, expected=1)
        assert report["synthetic_slots"] == 1 and report["missing_slots"] == 731
        assert report["provider_calls"] == 0 and report["failed_slots"] == 0
        record["attempts"][1]["generation_id"] = "another-generation"
        results.write_text(json.dumps(record) + "\n")
        report = run(*command, expected=1)
        assert "same generation" in report["invalid_records"][0]["reason"]
        record["attempts"][1]["generation_id"] = record["generation_id"]
        record["execution_sha256"] = "0" * 64
        results.write_text(json.dumps(record) + "\n")
        report = run(*command, expected=1)
        assert "execution identity" in report["invalid_records"][0]["reason"]
        record["execution_sha256"] = final["slots"][0]["execution_sha256"]
        record["mode"] = "real"
        record["critical_defects"] = []
        record["recovery"] = "unavailable"
        record["evidence"]["recovery"] = record["evidence"]["generation"]
        results.write_text(json.dumps(record) + "\n")
        report = run(*command, expected=1)
        assert "matching Android capture" in report["invalid_records"][0]["reason"]
        record["mode"] = "synthetic"
        del record["evidence"]["recovery"]
        results.write_text((json.dumps(record) + "\n") * 2)
        report = run(*command, expected=1)
        assert "already has a record" in report["invalid_records"][0]["reason"]
        assert report["gate"] == "blocked"

        results.write_text("")
        captures = root / "captures"
        android = captures / "android"
        android.mkdir(parents=True)
        slot = final["slots"][0]
        frozen = {"slot_id": slot["id"], "plan_sha256": plan_hash,
                  "request_sha256": slot["request_sha256"], "execution_sha256": slot["execution_sha256"]}
        checkpoint = {**frozen, "run_id": "8" * 32, "phase": "complete",
                      "fresh_draft": "offline-draft", "fresh_conversation": "offline-session",
                      "fresh_store": "offline-store", "chat_outcome": "complete-without-artifact",
                      "measurements": {"first_ui_ms": None, "completed_ui_ms": None, "provider_http_calls": None},
                      "provider_invocations": ["fixture-only"],
                      "device": {key: candidate["device"][key] for key in ("model", "os", "fingerprint")}}
        checkpoint.update({key: candidate[key] for key in ("revision", "source_sha256", "build_sha256")})
        (android / "report.json").write_bytes(encoded(checkpoint))
        manifest = {"slot": slot, "plan_sha256": plan_hash, "run_id": checkpoint["run_id"],
                    "execution": candidate, "apks": apks, "device": candidate["device"],
                    "command": ["offline-schema-fixture"], "status": "device-complete",
                    "artifacts": {"report.json": digest(encoded(checkpoint))}}
        (android / "capture.json").write_bytes(encoded(manifest))

        host = captures / "host"
        ref_slot = final["slots"][3]
        host_packet = {"format": 1, "slot_id": ref_slot["id"], "plan_sha256": plan_hash,
                       "request_sha256": ref_slot["request_sha256"], "execution_sha256": ref_slot["execution_sha256"],
                       "source": {key: reference_host[key] for key in
                                  ("revision", "source_sha256", "build_sha256", "route", "provider")},
                       "runtime": runtime, "mode": "synthetic", "origin": "synthetic", "status": "complete",
                       "generation_id": "9" * 32, "attempt_id": "a" * 32, "attempt": 1, "host_outcome": "failed",
                       "failure": "fixture-only", "comparison_evidence": {"native_rendering": "unavailable"}}
        first = host / "attempt-1"
        second = host / "attempt-2"
        first.mkdir(parents=True)
        second.mkdir()
        (first / "result.json").write_bytes(encoded(host_packet))
        correction = {**host_packet, "attempt": 2, "attempt_id": "b" * 32,
                      "previous_attempt_sha256": digest(encoded(host_packet))}
        (second / "result.json").write_bytes(encoded(correction))
        ingest = (*command, "--capture-dir", str(captures))
        report = run(*ingest, expected=1)
        assert report["incomplete_slots"] == 2 and report["missing_slots"] == 730
        assert report["provider_calls"] == 0
        assert report["captures"][slot["id"]]["observed_outcome"] == "complete-without-artifact"
        assert report["captures"][ref_slot["id"]]["attempts"][1]["attempt"] == 2
        assert "Host-only reference execution cannot pass native comparison." in report["blockers"]
        assert not report["invalid_captures"]

        functional = ("audit-functional", *ingest[1:])
        report = run(*functional, expected=1)
        assert report["scope"] == "android-and-reference-host-functionality"
        assert report["planned_slots"] == 732 and report["complete_slots"] == 0
        assert report["cases"]["extra:calendar"]["reference"] == ["missing"] * 3
        results.write_text(json.dumps({
            **frozen, "capture_sha256": digest(encoded(manifest)), "outcome": "refused",
        }) + "\n" + json.dumps({
            "slot_id": ref_slot["id"], "plan_sha256": plan_hash,
            "request_sha256": ref_slot["request_sha256"], "execution_sha256": ref_slot["execution_sha256"],
            "capture_sha256": digest(encoded(correction)), "outcome": "failed",
        }) + "\n")
        report = run(*functional, expected=1)
        assert "HTTP dispatch" in report["invalid_records"][0]["reason"]
        assert "live reference host" in report["invalid_records"][1]["reason"]
        assert report["complete_slots"] == 0

        def review_row(slot, capture, artifact, review_fields):
            identity = {"slot_id": slot["id"], "plan_sha256": plan_hash,
                        "request_sha256": slot["request_sha256"], "execution_sha256": slot["execution_sha256"]}
            proof = {**identity, "capture_sha256": capture, "artifact": artifact, "critical_defects": [],
                     "checks": {"read": {"status": "pass", "observed": "Fixture showed the seeded event."}},
                     **review_fields}
            proof_path = root / (slot["product"] + "-proof.json")
            proof_path.write_bytes(encoded(proof))
            return {**identity, "capture_sha256": capture, "outcome": "generated",
                    "review": {"path": proof_path.name, "sha256": digest(encoded(proof))}}

        calendar_slots = [item for item in final["slots"] if item["case_id"] == "extra:calendar"]
        android_slot, reference_slot = calendar_slots[0], calendar_slots[3]
        fixture_artifact = b"offline-program-fixture"
        fixture_sha = digest(fixture_artifact)
        valid_host = {**host_packet, "slot_id": reference_slot["id"], "mode": "live", "origin": "reference-host",
                      "request_sha256": reference_slot["request_sha256"], "host_outcome": "built", "failure": None,
                      "artifacts": {"program.vera": {"sha256": fixture_sha, "bytes": len(fixture_artifact),
                                                     "origin": "reference-host"}}}
        (second / "result.json").unlink()
        second.rmdir()
        (first / "result.json").write_bytes(encoded(valid_host))
        host_review = review_row(reference_slot, digest(encoded(valid_host)), fixture_sha, {})
        results.write_text(json.dumps(host_review) + "\n")
        report = run(*functional, expected=1)
        assert "host program" in report["invalid_records"][0]["reason"]
        program = first / "program.vera"
        program.write_bytes(fixture_artifact)
        report = run(*functional, expected=1)
        assert report["complete_slots"] == 1 and not report["invalid_records"]
        assert report["cases"]["extra:calendar"]["reference"] == ["pass", "missing", "missing"]
        program.write_bytes(b"changed")
        report = run(*functional, expected=1)
        assert "host program" in report["invalid_records"][0]["reason"]
        program.unlink()
        (first / "result.json").write_bytes(encoded(host_packet))
        second.mkdir()
        (second / "result.json").write_bytes(encoded(correction))

        corrected = {**checkpoint, "slot_id": android_slot["id"], "request_sha256": android_slot["request_sha256"],
                     "artifact_id": "offline-artifact", "chat_outcome": "artifact-committed",
                     "measurements": {"provider_http_calls": 2},
                     "operation_trace": {"http_dispatches": [{"at_ns": 100}, {"at_ns": 200}],
                                         "generation": [{"attempt": 1, "disposition": "correct"}, {"attempt": 2}]}}
        corrected_manifest = {**manifest, "slot": android_slot, "artifacts": {"report.json": digest(encoded(corrected))}}
        (android / "report.json").write_bytes(encoded(corrected))
        (android / "capture.json").write_bytes(encoded(corrected_manifest))
        corrected_hash = digest(encoded(corrected_manifest))
        for fields, complete in (({}, 0), ({"recovery": "pass"}, 1)):
            row = review_row(android_slot, corrected_hash, corrected["artifact_id"], fields)
            results.write_text(json.dumps(row) + "\n")
            report = run(*functional, expected=1)
            assert report["complete_slots"] == complete
            if not complete:
                assert "Review recovery" in report["invalid_records"][0]["reason"]
        (android / "report.json").write_bytes(encoded(checkpoint))
        (android / "capture.json").write_bytes(encoded(manifest))

        supplement = {**frozen, "mode": "real", "capture_sha256": digest(encoded(manifest))}
        results.write_text(json.dumps(supplement) + "\n")
        report = run(*ingest, expected=1)
        assert "measurement trace" in report["invalid_records"][0]["reason"]
        assert report["incomplete_slots"] == 2

        trace = {**frozen, "format": 1, "capture_sha256": supplement["capture_sha256"],
                 "generation_id": checkpoint["run_id"], "provider_count_source": "http-boundary",
                 "attempts": [{"number": 1, "generation_id": checkpoint["run_id"], "outcome": "refused",
                               "provider_calls": 1, "stages": {"fixture-only": 1}}]}
        trace_path = root / "trace.json"
        trace_path.write_bytes(encoded(trace))
        supplement.update(refusal_confirmed=True, interactions={}, critical_defects=[],
                          visual={"blind": False, "review_id": "fixture-only", "ratings": {}},
                          evidence={"telemetry": {"path": trace_path.name, "sha256": digest(encoded(trace)),
                                                  "origin": "real-run"}})
        results.write_text(json.dumps(supplement) + "\n")
        report = run(*ingest, expected=1)
        assert "blind visual ratings" in report["invalid_records"][0]["reason"]
        assert report["provider_calls"] == 0
        trace["generation_id"] = "c" * 32
        trace_path.write_bytes(encoded(trace))
        supplement["evidence"]["telemetry"]["sha256"] = digest(encoded(trace))
        results.write_text(json.dumps(supplement) + "\n")
        report = run(*ingest, expected=1)
        assert "frozen slot and operation" in report["invalid_records"][0]["reason"]

        checkpoint.update(chat_outcome="artifact-committed", operation_id="offline-operation")
        (android / "report.json").write_bytes(encoded(checkpoint))
        manifest["artifacts"]["report.json"] = digest(encoded(checkpoint))
        (android / "capture.json").write_bytes(encoded(manifest))
        supplement["capture_sha256"] = digest(encoded(manifest))
        trace.update(generation_id=checkpoint["operation_id"], capture_sha256=supplement["capture_sha256"],
                     clock="monotonic-ms", events={"send_accepted": 100})
        trace_path.write_bytes(encoded(trace))
        supplement["evidence"]["telemetry"]["sha256"] = digest(encoded(trace))
        results.write_text(json.dumps(supplement) + "\n")
        report = run(*ingest, expected=1)
        assert "measured monotonic frame times" in report["invalid_records"][0]["reason"]
        assert report["incomplete_slots"] == 2 and report["provider_calls"] == 0
        results.write_text("")

        correction["previous_attempt_sha256"] = "0" * 64
        (second / "result.json").write_bytes(encoded(correction))
        report = run(*ingest, expected=1)
        assert "correction lineage" in report["invalid_captures"][0]["reason"]
        (first / "result.json").write_bytes(encoded({**host_packet, "plan_sha256": "0" * 64}))
        report = run(*ingest, expected=1)
        assert "frozen" in report["invalid_captures"][0]["reason"]

        duplicate = captures / "duplicate"
        duplicate.mkdir()
        (duplicate / "report.json").write_bytes(encoded(checkpoint))
        (duplicate / "capture.json").write_bytes(encoded(manifest))
        report = run(*ingest, expected=1)
        assert any("already has a capture" in item["reason"] for item in report["invalid_captures"])
    print("PASS: 121 English requests, 726 English slots, 45 held-out requests, and blocked offline evidence.")


if __name__ == "__main__":
    main(sys.argv[1])
