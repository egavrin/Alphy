"""Harness boundaries, recorded before harness implementation."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("harness", Path(__file__).with_name("run.py"))
harness = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(harness)


class HarnessBoundaries(unittest.TestCase):
    def failed_fixture(self, directory):
        manifest, report = self.evidence_fixture(directory)
        for name in ("before.png", "after.png", "motion.mp4", "artifact.json"):
            (directory / name).unlink()
        for name in ("instrumentation.log", "capture.log"):
            (directory / name).write_text("Synthetic failed generation evidence\n")
        report.update(generation="failed", phase="failed", failureType="CloudFailure",
                      generationAttempts=[{"attempt": index, "outcome": "rejected", "stage": "definition",
                                           "failureCode": "widget_spec", "category": "tool_json"} for index in (1, 2)])
        for key in ("artifactId", "specSha256", "database"):
            report.pop(key)
        (directory / "report.json").write_text(json.dumps(report))
        manifest["checks"] = [{**row, "status": "UNAVAILABLE", "observed": "Generation failed", "evidence": ["report.json"]}
                              for row in manifest["checks"]]
        manifest["artifacts"] = {path.name: harness.digest(path) for path in directory.iterdir() if path.name != "manifest.json"}
        (directory / "manifest.json").write_text(json.dumps(manifest))
        return manifest, report

    def test_failed_evidence_is_complete_without_screenshots_but_never_workflow_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.failed_fixture(directory)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual("FAILED", harness.verify_failed(directory))
            self.assertNotIn("PASS", output.getvalue())
            with self.assertRaises(ValueError):
                harness.verify(directory)

    def test_failed_evidence_rejects_incomplete_attempts_false_success_and_rebound_identity(self):
        changes = [("phase", "generation"), ("generation", "accepted"), ("request", "wrong"),
                   ("generationAttempts", []), ("generationAttempts", [{"attempt": 2, "outcome": "rejected"}]),
                   ("generationAttempts", [{"attempt": 1, "outcome": "accepted", "stage": "smoke", "failureCode": "none", "category": "unknown"}])]
        for key, value in changes:
            with self.subTest(key=key, value=value), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                manifest, report = self.failed_fixture(directory)
                report[key] = value
                (directory / "report.json").write_text(json.dumps(report))
                manifest["artifacts"]["report.json"] = harness.digest(directory / "report.json")
                (directory / "manifest.json").write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    harness.verify_failed(directory)

    def test_failed_evidence_requires_hashed_report_identity_and_capture_records(self):
        for name in ("report.json", "identity.json", "instrumentation.log", "capture.log"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                manifest, _ = self.failed_fixture(directory)
                del manifest["artifacts"][name]
                (directory / "manifest.json").write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    harness.verify_failed(directory)

    def evidence_fixture(self, directory, scenario=4, repetition=1):
        directory.mkdir(parents=True, exist_ok=True)
        case = harness.scenarios()[scenario - 1]
        run_id = f"integrity-fixture-{scenario}-{repetition}"
        for name in ("before.png", "after.png", "motion.mp4"):
            (directory / name).write_bytes(b"synthetic integrity fixture")
        (directory / "artifact.json").write_text("{}")
        tested = {"revision": "a" * 40, "sourceSha256": "b" * 64}
        (directory / "identity.json").write_text(json.dumps(tested))
        report = {"scenario": scenario, "repetition": repetition, "runId": run_id, "request": case["request"],
                  "mode": "real", "generation": "accepted", "phase": "complete", **tested,
                  "specSha256": harness.digest(directory / "artifact.json"), "artifactId": run_id, "database": run_id + ".db"}
        (directory / "report.json").write_text(json.dumps(report))
        manifest = {"scenario": scenario, "repetition": repetition, "runId": run_id, "request": case["request"], "mode": "real",
                    "artifacts": {path.name: harness.digest(path) for path in directory.iterdir()},
                    "checks": [{"id": key, "status": "PASS", "expected": value, "observed": "Synthetic integrity fixture", "evidence": ["after.png"]}
                               for key, value in case["checks"].items()]}
        (directory / "manifest.json").write_text(json.dumps(manifest))
        return manifest, report

    def test_verify_binds_manifest_report_and_canonical_request_before_results(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest, report = self.evidence_fixture(directory)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertTrue(harness.verify(directory))
            changes = [(side, field, value) for side in ("manifest", "report") for field, value in (
                ("scenario", 1), ("repetition", 3), ("runId", "another-run"), ("request", "another request"))]
            changes += [("both", "request", "same noncanonical request"), ("both", "scenario", 7),
                        ("both", "repetition", 4), ("both", "runId", "")]
            for side, field, value in changes:
                with self.subTest(side=side, field=field):
                    changed_manifest = {**manifest}
                    changed_report = {**report}
                    if side in ("manifest", "both"):
                        changed_manifest[field] = value
                    if side in ("report", "both"):
                        changed_report[field] = value
                    (directory / "report.json").write_text(json.dumps(changed_report))
                    changed_manifest["artifacts"] = {**manifest["artifacts"], "report.json": harness.digest(directory / "report.json")}
                    (directory / "manifest.json").write_text(json.dumps(changed_manifest))
                    with patch.object(harness, "validate_results", side_effect=AssertionError("Identity must fail before results")):
                        with self.assertRaises(ValueError):
                            harness.verify(directory)

    def test_audit_rejects_a_report_rebound_to_another_manifest_slot(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for scenario, repetition in harness.matrix(harness.scenarios()):
                directory = root / f"{scenario}-{repetition}"
                manifest, _ = self.evidence_fixture(directory, scenario, repetition)
                if (scenario, repetition) == (1, 3):
                    case = harness.scenarios()[3]
                    manifest.update(scenario=4, repetition=1, runId="different-run", request=case["request"])
                    manifest["checks"] = [{"id": key, "status": "PASS", "expected": value, "observed": "Synthetic integrity fixture", "evidence": ["after.png"]}
                                          for key, value in case["checks"].items()]
                    (directory / "manifest.json").write_text(json.dumps(manifest))
            with patch("sys.argv", ["run.py", "audit", str(root)]):
                with contextlib.redirect_stdout(io.StringIO()):
                    with self.assertRaises(ValueError):
                        harness.main()

    def test_key_preserves_environment_and_reads_only_dotenv_data(self):
        sentinel = "synthetic-credential-environment"
        with patch.object(Path, "read_text", side_effect=AssertionError("Unexpected dotenv read")):
            self.assertEqual(sentinel, harness.read_live_key({"DEEPSEEK_API_KEY": sentinel}))
        with patch.object(Path, "read_text", return_value='UNRELATED=value\nexport DEEPSEEK_API_KEY="literal-$(no-execution)"\n'):
            self.assertEqual("literal-$(no-execution)", harness.read_live_key({}))
        with patch.object(Path, "read_text", side_effect=FileNotFoundError):
            with self.assertRaisesRegex(RuntimeError, "DEEPSEEK_API_KEY"):
                harness.read_live_key({})

    def test_socket_transfer_never_places_key_in_commands_and_removes_forward(self):
        sentinel = "synthetic-credential-transfer"
        connection = Mock()
        connection.__enter__ = Mock(return_value=connection)
        connection.__exit__ = Mock(return_value=False)
        connection.recv.return_value = b"\x01"
        payloads = []
        connection.sendall.side_effect = lambda data: payloads.append(bytes(data))
        with patch.object(harness.subprocess, "run", return_value=Mock(stdout="12345\n", returncode=0)) as execute:
            with patch.object(harness.socket, "create_connection", return_value=connection):
                harness.transfer_key("adb", "synthetic-run", sentinel)
        self.assertEqual(["adb", "-s", "emulator-5556", "forward", "--remove", "tcp:12345"], execute.call_args_list[-1].args[0])
        self.assertNotIn(sentinel, repr([call.args for call in execute.call_args_list]))
        self.assertTrue(all("DEEPSEEK_API_KEY" not in call.kwargs["env"] for call in execute.call_args_list))
        self.assertEqual(b"\x00\x00\x00\x1d" + sentinel.encode(), payloads[0])

    def test_transfer_failure_is_sanitized_and_cleans_forward(self):
        sentinel = "synthetic-credential-failure"
        with patch.object(harness.subprocess, "run", return_value=Mock(stdout="12345\n", returncode=0)) as execute:
            with patch.object(harness.socket, "create_connection", side_effect=OSError(sentinel)):
                with self.assertRaises(RuntimeError) as failure:
                    harness.transfer_key("adb", "synthetic-run", sentinel)
        self.assertNotIn(sentinel, str(failure.exception))
        self.assertEqual("tcp:12345", execute.call_args_list[-1].args[0][-1])

    def test_exact_independent_matrix(self):
        cases = harness.scenarios()
        self.assertEqual([1, 2, 3, 4, 5, 6], [case["id"] for case in cases])
        self.assertEqual(18, len(harness.matrix(cases)))
        self.assertTrue(all(case["checks"] for case in cases))
        self.assertEqual("Generate arkanoid game", cases[3]["request"])

    def test_device_is_fixed_and_commands_are_qualified(self):
        self.assertEqual(["adb", "-s", "emulator-5556", "get-state"], harness.adb_command("adb", "get-state"))
        command = harness.instrument_command("adb", 1, 2, "a" * 40, "b" * 64, False, 1)
        self.assertNotIn("emulator-5554", command)
        self.assertNotIn("connectedDebugAndroidTest", command)
        self.assertNotIn("uninstall", command)
        self.assertIn("composeLive", command)
        self.assertIn("false", command)

    def test_manifest_rejects_missing_and_duplicate_checks(self):
        case = harness.scenarios()[0]
        with self.assertRaises(ValueError):
            harness.validate_results(case, [])
        rows = [{"id": key, "status": "PASS", "expected": "x", "observed": "x", "evidence": ["after.png"]} for key in case["checks"]]
        harness.validate_results(case, rows)
        with self.assertRaises(ValueError):
            harness.validate_results(case, rows + rows[:1])
        rows[0]["evidence"] = []
        with self.assertRaises(ValueError):
            harness.validate_results(case, rows)

    def test_hashes_detect_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_text("one")
            before = harness.digest(path)
            path.write_text("two")
            self.assertNotEqual(before, harness.digest(path))

    def test_unavailable_is_not_pass(self):
        case = harness.scenarios()[0]
        rows = [{"id": key, "status": "UNAVAILABLE", "expected": "x", "observed": "not run", "evidence": ["report.json"]} for key in case["checks"]]
        self.assertFalse(harness.validate_results(case, rows))

    def test_complete_matrix_requires_distinct_slots_and_artifacts(self):
        rows = [{"scenario": scenario, "repetition": repeat, "artifactId": str((scenario, repeat)),
                 "database": str((scenario, repeat)), "mode": "real"} for scenario, repeat in harness.matrix(harness.scenarios())]
        harness.validate_matrix(rows)
        with self.assertRaises(ValueError):
            harness.validate_matrix(rows[:-1])
        rows[-1]["artifactId"] = rows[0]["artifactId"]
        with self.assertRaises(ValueError):
            harness.validate_matrix(rows)

    def test_coverage_preserves_pinned_reference_and_current_sources(self):
        coverage = json.loads((harness.ROOT / "docs/parity/compose-coverage.json").read_text())
        inventory = json.loads((harness.ROOT / "docs/parity/inventory.json").read_text())
        reference = {row["key"] for row in inventory["entries"] if row["kind"] == "ui"}
        self.assertEqual(62, len(reference))
        self.assertEqual(reference, {row["reference"] for row in coverage["reference_ui"]})
        self.assertEqual(inventory["reference_commit"], coverage["reference_commit"])
        self.assertEqual(49, len(set(coverage["catalog_types"])))
        for path, expected in coverage["source_sha256"].items():
            self.assertEqual(expected, harness.digest(harness.ROOT / path), path)


if __name__ == "__main__":
    unittest.main()
