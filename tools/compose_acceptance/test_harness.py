"""Harness boundaries, recorded before harness implementation."""
import importlib.util
import contextlib
import io
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("harness", Path(__file__).with_name("run.py"))
harness = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(harness)


def suite_status(current, total, method, code):
    return (f"INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: current={current}\n"
            f"INSTRUMENTATION_STATUS: numtests={total}\nINSTRUMENTATION_STATUS: test={method}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


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

    def test_failed_evidence_checks_staged_generation_order(self):
        def record(stage, outcome, code, disposition, attempt=1, **extra):
            return dict(attempt=attempt, outcome=outcome, stage=stage, failureCode=code,
                        category="unknown", disposition=disposition, **extra)

        planned = record("plan", "completed", "none", "continue")
        invalid_plan = record("plan", "rejected", "widget_plan", "correct")
        sections = record("selector", "completed", "none", "continue", selectorBatch="sections")
        parts = record("selector", "completed", "none", "continue", selectorBatch="parts")
        unavailable_parts = record("selector", "providerFailure", "incomplete", "continue", selectorBatch="parts")
        fallback = record("selector", "rejected", "widget_selector", "continue")
        question = record("selector", "rejected", "widget_questions", "finish")
        mixed = record("assembly", "rejected", "widget_incomplete", "finish")
        auth = record("provider", "providerFailure", "auth", "finish")
        too_long = record("provider", "providerFailure", "response_length", "correct")
        validated = record("smoke", "validated", "none", "finish")
        plan_repairs = [{**invalid_plan, "attempt": number} for number in range(1, 4)]
        writer = record("behavior", "rejected", "widget_behavior", "correct")
        writer_repairs = [{**writer, "attempt": number} for number in range(4, 7)]
        both_budgets = plan_repairs + [{**planned, "attempt": 4}] + writer_repairs
        bad_patch = record("provider", "rejected", "widget_patch", "correct", attempt=4)
        cases = [
            (True, both_budgets + [{**writer, "attempt": 7, "disposition": "finish"}], "CloudFailure"),
            (True, plan_repairs + [{**planned, "attempt": 4}, bad_patch, {**auth, "attempt": 5}], "CloudFailure"),
            (False, plan_repairs + [{**invalid_plan, "attempt": 4}, {**planned, "attempt": 5},
                                    {**auth, "attempt": 5}], "CloudFailure"),
            (False, both_budgets + [{**writer, "attempt": 7}, {**auth, "attempt": 8}], "CloudFailure"),
            (True, [record("plan", "providerFailure", "auth", "finish")], "CloudFailure"),
            (True, [invalid_plan, {**invalid_plan, "attempt": 2, "disposition": "finish"}], "CloudFailure"),
            (True, [invalid_plan, {**planned, "attempt": 2}, {**auth, "attempt": 2}], "CloudFailure"),
            (True, [planned, sections, fallback, question], "CloudFailure"),
            (True, [planned, sections, record("selector", "providerFailure", "unknown", "finish")], "IllegalArgumentException"),
            (True, [planned, sections, parts, mixed], "CloudFailure"),
            (True, [planned, sections, unavailable_parts, fallback, auth], "CloudFailure"),
            (True, [planned, sections, fallback, too_long, {**auth, "attempt": 2}], "CloudFailure"),
            (True, [planned, validated], "SQLiteException"),
            (False, [planned, validated], ""),
            (False, [planned], "CloudFailure"),
            (False, [planned, parts, auth], "CloudFailure"),
            (False, [planned, sections, fallback, parts, auth], "CloudFailure"),
            (False, [planned, sections, mixed], "CloudFailure"),
            (False, [planned, sections, unavailable_parts, auth], "CloudFailure"),
            (False, [planned, sections, question, auth], "CloudFailure"),
            (False, [invalid_plan, planned, auth], "CloudFailure"),
            (False, [invalid_plan, {**planned, "attempt": 2}, {**too_long, "attempt": 2}], "CloudFailure"),
            (False, [planned, {**auth, "disposition": "correct"}, {**auth, "attempt": 2}], "CloudFailure"),
            (False, [planned, {key: value for key, value in auth.items() if key != "disposition"}], "CloudFailure"),
            (False, [planned, {**auth, "attempt": 2}], "CloudFailure"),
        ]
        for valid, attempts, failure_type in cases:
            with self.subTest(valid=valid, attempts=attempts), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                manifest, report = self.failed_fixture(directory)
                report.update(generationAttempts=attempts, failureType=failure_type)
                (directory / "report.json").write_text(json.dumps(report))
                manifest["artifacts"]["report.json"] = harness.digest(directory / "report.json")
                (directory / "manifest.json").write_text(json.dumps(manifest))
                if valid:
                    output = io.StringIO()
                    with contextlib.redirect_stdout(output):
                        self.assertEqual("FAILED", harness.verify_failed(directory))
                    self.assertNotIn("PASS", output.getvalue())
                else:
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

    def test_identity_covers_the_coverage_pins(self):
        self.assertIn("docs/parity/compose-coverage.json", harness.identity(apks=False)["files"])

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
        keys = sorted(row["reference"] for row in coverage["reference_ui"])
        self.assertEqual(62, len(keys))
        self.assertEqual(len(keys), len(set(keys)))
        fingerprint = hashlib.sha256(json.dumps(keys, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        self.assertEqual("d0847fb9440e832b9c2babbf641a3bde2d5acb1bf2f39060d9db4197fb1d67c0", fingerprint)
        self.assertEqual("7ee06af1941729b00a110493ad9d1d9b5b127d17", coverage["reference_commit"])
        self.assertEqual(72, len(coverage["catalog_types"]))
        self.assertEqual(72, len(set(coverage["catalog_types"])))
        for path, expected in coverage["source_sha256"].items():
            self.assertEqual(expected, harness.digest(harness.ROOT / path), path)

    def test_suite_outcome_reads_raw_instrumentation(self):
        x, y = (suite_status(i, 2, method, 1) for i, method in enumerate(("x", "y"), 1))
        y_end = suite_status(2, 2, "y", 0)
        passed = (x + suite_status(1, 2, "x", 0) + y + y_end +
                  "INSTRUMENTATION_RESULT: stream=\n\nTime: 1.2\n\nOK (2 tests)\n\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual(harness.suite_outcome(passed, set()), {"observed": "passed", "tests": 2, "failures": [], "skipped": []})
        failed = (x + "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: no\n\tat a.B.x(B.kt:1)\n" +
                  suite_status(1, 2, "x", -2) + y + y_end +
                  "INSTRUMENTATION_RESULT: stream=\n\nFAILURES!!!\nTests run: 2,  Failures: 1\n\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual(harness.suite_outcome(failed, set()), {"observed": "failed", "tests": 2, "failures": ["a.B#x"], "skipped": []})
        self.assertEqual(harness.suite_outcome(failed, {"a.B#x"})["observed"], "passed")
        self.assertEqual("failed", harness.suite_outcome(failed.replace("Failures: 1", "Failures: 2"), {"a.B#x"})["observed"])
        self.assertEqual("failed", harness.suite_outcome(passed.replace(y_end, "").replace("2 tests", "1 test"), set())["observed"])
        assumed = (suite_status(1, 1, "y", 1) + suite_status(1, 1, "y", -4) +
                   "INSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\n\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual(harness.suite_outcome(assumed, set()), {"observed": "failed", "tests": 1, "failures": [], "skipped": ["a.B#y"]})
        self.assertEqual(harness.suite_outcome(assumed, set(), {"a.B#y"})["observed"], "passed")
        crashed = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        self.assertEqual(harness.suite_outcome(crashed, set())["observed"], "failed")

    def test_suite_command_uses_only_the_test_emulator(self):
        command = harness.suite_instrument_command("adb", ["a.B#x", "c.D"], "abc123", serial="emulator-5560")
        self.assertEqual(command[:3], ["adb", "-s", "emulator-5560"])
        self.assertEqual(command[command.index("class") + 1], "a.B#x,c.D")
        self.assertEqual(command[-1], "com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner")
        self.assertNotIn("class", harness.suite_instrument_command("adb", [], "abc123"))

    def test_suite_build_failure_records_no_apk_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = harness.argparse.Namespace(label="red", expect="build-failed", baseline=None, targets=[],
                                              adb="adb", serial="emulator-5556", output=Path(temporary))
            run = harness.subprocess.run
            def execute(command, **options):
                if command[0] == "git":
                    return run(command, **options)
                return Mock(returncode=1 if command[0] == "./gradlew" else 0, stdout=self.emulator_reply(command))
            with patch.object(harness.subprocess, "run", side_effect=execute), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, harness.suite(args))
            record = json.loads((Path(temporary) / "red/run.json").read_text())
            self.assertEqual("build-failed", record["observed"])
            self.assertIsNone(record["apks"])
            self.assertIsNone(record["installedApkSha256"])

    def test_suite_outcome_fails_a_failure_summary_without_a_failed_test(self):
        unattributed = ("INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: test=x\nINSTRUMENTATION_STATUS_CODE: 0\n"
                        "INSTRUMENTATION_RESULT: stream=\n\nFAILURES!!!\nTests run: 1,  Failures: 1\n\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual({"observed": "failed", "tests": 1, "failures": [], "skipped": []}, harness.suite_outcome(unattributed, set()))
        self.assertEqual("failed", harness.suite_outcome(unattributed, {"a.B#x"})["observed"])

    def test_suite_refuses_shell_words_in_targets_and_a_used_label_before_any_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            (Path(temporary) / "used").mkdir()
            for targets, named in ((["a.B$c"], "a.B$c"), (["a.B#x -e syntheticSchemaDiagnostic true"], "syntheticSchemaDiagnostic"),
                                   (["a.B#x", "c.D"], "used")):
                with self.subTest(targets=targets):
                    args = harness.argparse.Namespace(label="used", expect="passed", baseline=None, targets=targets,
                                                      adb="/nonexistent/adb", serial="emulator-5556", output=Path(temporary))
                    with patch.object(harness.subprocess, "run") as execute, self.assertRaises(SystemExit) as refused:
                        harness.suite(args)
                    execute.assert_not_called()
                    self.assertIn(named, str(refused.exception))

    SUITE_STARTED = suite_status(1, 1, "x", 1)
    SUITE_PASSED = (SUITE_STARTED + suite_status(1, 1, "x", 0) +
                    "INSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\n\n\nINSTRUMENTATION_CODE: -1\n")
    SUITE_FAILED = (SUITE_STARTED + suite_status(1, 1, "x", -2) +
                    "INSTRUMENTATION_RESULT: stream=\n\nFAILURES!!!\nTests run: 1,  Failures: 1\n\n\nINSTRUMENTATION_CODE: -1\n")

    @staticmethod
    def emulator_reply(command):
        return {"get-state": "device", "ro.kernel.qemu": "1", "ro.build.version.sdk": "29",
                "ro.build.fingerprint": "fixture/sdk/29", "webviewupdate": "WebView 133 fixture"}.get(command[-1], "")

    def fake_suite(self, output, label, instrumentation=SUITE_PASSED, installed="f" * 64, test_install=0, baseline=None,
                   *, serial="emulator-5560", emulator=True, installed_test="f" * 64, failed_command=None):
        """Run suite() against a fake device whose built and installed APK hashes are "f" * 64 unless changed."""
        calls = []
        run = harness.subprocess.run
        def execute(command, **options):
            calls.append((command, options))
            if command[0] == "git":
                return run(command, **options)
            if "instrument" in command:
                options["stdout"].write(instrumentation)
            reply = self.emulator_reply(command)
            if command[-1] == "ro.kernel.qemu" and not emulator:
                reply = "0"
            elif "pm" in command:
                reply = "package:/data/app/" + command[-1] + "/base.apk\n"
            elif "sha256sum" in command:
                reply = (installed_test if ".test/" in command[-1] else installed) + "  " + command[-1]
            returncode = test_install if command[-1] == harness.APKS[1] else int(failed_command in command)
            return Mock(returncode=returncode, stdout=reply)
        args = harness.argparse.Namespace(label=label, expect="passed", baseline=baseline, targets=["a.B#x"],
                                          adb="/nonexistent/adb", serial=serial, output=output)
        with patch.object(harness.subprocess, "run", side_effect=execute), patch.object(harness, "digest", return_value="f" * 64), \
                contextlib.redirect_stdout(io.StringIO()):
            code = harness.suite(args)
        return code, json.loads((output / label / "run.json").read_text())["observed"], calls

    def test_suite_fails_when_the_device_does_not_hold_the_built_apks(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            self.assertEqual((0, "passed"), self.fake_suite(output, "same")[:2])
            self.assertEqual((1, "failed"), self.fake_suite(output, "other-installed-hash", installed="0" * 64)[:2])
            self.assertEqual((1, "failed"), self.fake_suite(output, "test-install-failed", test_install=1)[:2])
            self.assertEqual((1, "failed"), self.fake_suite(output, "other-test-apk", installed_test="0" * 64)[:2])
            self.assertEqual((1, "failed"), self.fake_suite(output, "failed-package-lookup", failed_command="pm")[:2])
            self.assertEqual((1, "failed"), self.fake_suite(output, "failed-installed-hash", failed_command="sha256sum")[:2])

    def test_suite_records_the_selected_emulator_and_rejects_other_devices(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            code, _, calls = self.fake_suite(output, "selected")
            self.assertEqual(0, code)
            self.assertTrue(all(c[1:3] == ["-s", "emulator-5560"] for c, _ in calls if c[0] == "/nonexistent/adb"))
            record = json.loads((output / "selected/run.json").read_text())
            self.assertEqual("emulator-5560", record["serial"])
            self.assertEqual({"api": 29, "fingerprint": "fixture/sdk/29", "webView": "WebView 133 fixture"}, record["device"])
            self.assertEqual("f" * 64, record["installedTestApkSha256"])
            code, _, calls = self.fake_suite(output, "not-emulator", emulator=False)
            self.assertEqual(1, code)
            self.assertFalse(any(c[0] == "./gradlew" or "install" in c for c, _ in calls))
            with self.assertRaisesRegex(SystemExit, "emulator serial"):
                self.fake_suite(output, "physical-serial", serial="physical-device")

    def test_suite_rejects_a_finished_empty_run(self):
        empty = self.SUITE_PASSED.replace("1 test", "0 tests")
        self.assertEqual("failed", harness.suite_outcome(empty, set())["observed"])

    def test_suite_passes_only_failures_that_the_baseline_knows(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "baseline.json").write_text(json.dumps({"failures": ["a.B#x"], "skipped": []}))
            self.assertEqual((1, "failed"), self.fake_suite(output, "unknown", self.SUITE_FAILED)[:2])
            self.assertEqual((0, "passed"), self.fake_suite(output, "known", self.SUITE_FAILED, baseline=output / "baseline.json")[:2])

    def test_suite_gives_the_key_to_no_command_and_the_build_paths_only_to_gradle(self):
        environment = {key: value for key, value in harness.os.environ.items() if key not in ("JAVA_HOME", "ANDROID_HOME")}
        with tempfile.TemporaryDirectory() as temporary, \
                patch.dict(harness.os.environ, {**environment, "DEEPSEEK_API_KEY": "test-value"}, clear=True):
            _, observed, calls = self.fake_suite(Path(temporary), "environment")
        self.assertEqual("passed", observed)
        self.assertEqual([], [command for command, options in calls if "DEEPSEEK_API_KEY" in options["env"]])
        self.assertNotIn("test-value", json.dumps([command for command, _ in calls]))
        build_paths = [(command[0], options["env"].get("JAVA_HOME"), options["env"].get("ANDROID_HOME"))
                       for command, options in calls if {"JAVA_HOME", "ANDROID_HOME"} & options["env"].keys()]
        self.assertEqual([("./gradlew", "/opt/homebrew/opt/openjdk@17", "/opt/homebrew/share/android-commandlinetools")], build_paths)


if __name__ == "__main__":
    unittest.main()
