"""Harness boundaries, recorded before harness implementation."""
import importlib.util
import contextlib
import io
import hashlib
import json
import sys
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
        report.update(generation="failed", phase="failed", failureType="CloudFailure")
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

    def test_failed_evidence_rejects_false_success_and_rebound_identity(self):
        changes = [("phase", "generation"), ("generation", "accepted"), ("request", "wrong")]
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

    KEY_NAMES = ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "OPENROUTER_API_KEY", "DEEPSEEK_API_KEY")

    def test_account_reads_the_provider_then_only_its_key(self):
        sentinel = "synthetic-credential-environment"
        with patch.object(Path, "read_text", side_effect=AssertionError("Unexpected dotenv read")):
            self.assertEqual(("openrouter", sentinel), harness.read_live_account(
                {"ALPHY_AI_PROVIDER": "openrouter", "OPENROUTER_API_KEY": sentinel, "DEEPSEEK_API_KEY": "other"}))
        dotenv = ('UNRELATED=value\nALPHY_AI_PROVIDER=anthropic\nDEEPSEEK_API_KEY=other\n'
                  'export ANTHROPIC_API_KEY="literal-$(no-execution)"\n')
        with patch.object(Path, "read_text", return_value=dotenv):
            self.assertEqual(("anthropic", "literal-$(no-execution)"), harness.read_live_account({}))
        # The key of another provider is never a fallback.
        with patch.object(Path, "read_text", side_effect=FileNotFoundError):
            with self.assertRaisesRegex(RuntimeError, "OPENAI_API_KEY") as failure:
                harness.read_live_account({"ALPHY_AI_PROVIDER": "openai", "DEEPSEEK_API_KEY": sentinel})
        self.assertNotIn(sentinel, str(failure.exception))

    def test_account_refuses_an_absent_or_unknown_provider_before_any_key(self):
        for value in (None, "", "gemini", "OpenAI", "deepseek "):
            seen = []

            class Recording(dict):
                def get(self, name, default=None):
                    seen.append(name)
                    return super().get(name, default)

            environment = Recording({name: "synthetic-credential-key" for name in self.KEY_NAMES})
            if value is not None:
                environment["ALPHY_AI_PROVIDER"] = value
            with self.subTest(value=value), patch.object(Path, "read_text", side_effect=FileNotFoundError):
                with self.assertRaisesRegex(RuntimeError, "openai, anthropic, openrouter, or deepseek") as failure:
                    harness.read_live_account(environment)
                self.assertEqual(["ALPHY_AI_PROVIDER"], seen)
                self.assertNotIn("gemini", str(failure.exception))

    def test_model_is_optional_and_checked_only_by_form(self):
        with patch.object(Path, "read_text", side_effect=FileNotFoundError):
            self.assertIsNone(harness.selected_model({}))
            self.assertEqual("openai/gpt-6.1-terra", harness.selected_model({"ALPHY_AI_MODEL": "openai/gpt-6.1-terra"}))
            self.assertEqual("x:1_a.b-c", harness.selected_model({"ALPHY_AI_MODEL": "x:1_a.b-c"}))
            for value in ("two words", "m" * 101, "$(model)", "model;x", "модель"):
                with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, "ALPHY_AI_MODEL") as failure:
                    harness.selected_model({"ALPHY_AI_MODEL": value})
                self.assertNotIn(value, str(failure.exception))
        with patch.object(Path, "read_text", return_value="ALPHY_AI_MODEL=claude-opus-5-5\n"):
            self.assertEqual("claude-opus-5-5", harness.selected_model({}))

    def test_child_environment_holds_no_key(self):
        with patch.dict(harness.os.environ, {**{name: "test-value" for name in self.KEY_NAMES},
                                             "ALPHY_AI_PROVIDER": "openai", "PATH": "/usr/bin"}, clear=True):
            self.assertEqual({"ALPHY_AI_PROVIDER": "openai", "PATH": "/usr/bin"}, harness.child_environment())

    @staticmethod
    def connection(payloads):
        connection = Mock()
        connection.__enter__ = Mock(return_value=connection)
        connection.__exit__ = Mock(return_value=False)
        connection.recv.return_value = b"\x01"
        connection.sendall.side_effect = lambda data: payloads.append(bytes(data))
        return connection

    def test_socket_transfer_sends_the_provider_and_key_and_never_places_the_key_in_commands(self):
        sentinel = "synthetic-credential-transfer"
        payloads = []
        with patch.dict(harness.os.environ, {name: "test-value" for name in self.KEY_NAMES}), \
                patch.object(harness.subprocess, "run", return_value=Mock(stdout="12345\n", returncode=0)) as execute, \
                patch.object(harness.socket, "create_connection", return_value=self.connection(payloads)):
            harness.transfer_key("adb", "synthetic-run", ("anthropic", sentinel))
        self.assertEqual(["adb", "-s", "emulator-5556", "forward", "--remove", "tcp:12345"], execute.call_args_list[-1].args[0])
        self.assertNotIn(sentinel, repr([call.args for call in execute.call_args_list]))
        self.assertTrue(all(name not in call.kwargs["env"] for call in execute.call_args_list for name in self.KEY_NAMES))
        envelope = b"anthropic\n" + sentinel.encode()
        self.assertEqual(harness.struct.pack("!I", len(envelope)) + envelope, payloads[0])

    def test_envelope_refuses_an_unknown_provider_and_an_unsafe_key_before_any_command(self):
        for provider, key in (("gemini", "synthetic-key"), ("", "synthetic-key"), ("openai", ""), ("openai", "two words"),
                              ("openai", "line\nbreak"), ("openai", "tab\tkey"), ("openai", "ключ"),
                              ("openai", "k" * (8192 - len("openai\n") + 1))):
            with self.subTest(provider=provider, key=key[:20]), patch.object(harness.subprocess, "run") as execute:
                with self.assertRaises(RuntimeError) as failure:
                    harness.transfer_key("adb", "synthetic-run", (provider, key))
                execute.assert_not_called()
                if key:
                    self.assertNotIn(key, str(failure.exception))
        payloads = []
        longest = "k" * (8192 - len("openai\n"))
        with patch.object(harness.subprocess, "run", return_value=Mock(stdout="12345\n", returncode=0)), \
                patch.object(harness.socket, "create_connection", return_value=self.connection(payloads)):
            harness.transfer_key("adb", "synthetic-run", ("openai", longest))
        self.assertEqual(4 + 8192, len(payloads[0]))

    def test_transfer_failure_is_sanitized_and_cleans_forward(self):
        sentinel = "synthetic-credential-failure"
        with patch.object(harness.subprocess, "run", return_value=Mock(stdout="12345\n", returncode=0)) as execute:
            with patch.object(harness.socket, "create_connection", side_effect=OSError(sentinel)):
                with self.assertRaises(RuntimeError) as failure:
                    harness.transfer_key("adb", "synthetic-run", ("deepseek", sentinel))
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
        self.assertNotIn("aiModel", command)
        command = harness.instrument_command("adb", 1, 2, "a" * 40, "b" * 64, True, 1, model="claude-opus-5-5")
        self.assertEqual("claude-opus-5-5", command[command.index("aiModel") + 1])

    def fake_device(self, temporary, environment, device_report, call):
        """[call] against a fake emulator that holds the built APKs, asks once for the credential, and then gives
        [device_report]. Returns the result of [call], the transferred accounts, and each command with its options."""
        transfers, calls = [], []
        def reply(command, **options):
            calls.append((command, options))
            stdout = {"app_search": self.appsearch_dump(0), "ro.kernel.qemu": "1\n"}.get(command[-1], "fixture\n")
            if command[-1].endswith("/report.json"):
                stdout = json.dumps({"phase": "credentialReady"})
            elif "pm" in command:
                stdout = f"package:/data/app/{command[-1]}/base.apk\n"
            elif "sha256sum" in command:
                stdout = "f" * 64 + "  " + command[-1]
            elif "pull" in command:
                (Path(command[-1]) / "report.json").write_text(json.dumps(device_report))
            return Mock(stdout=stdout, stderr="", returncode=0)
        def start(command, stdout, **options):
            calls.append((command, options))
            stdout.write("OK (1 test)\n")
            return Mock(**{"poll.side_effect": [None] + [0] * 5, "returncode": 0})
        tested = {"revision": "a" * 40, "sourceSha256": "b" * 64, "apks": {path: "f" * 64 for path in harness.APKS}}
        with patch.dict(harness.os.environ, environment, clear=True), \
                patch.object(harness.Path, "home", return_value=Path(temporary)), \
                patch.object(harness, "identity", return_value=tested), \
                patch.object(harness, "transfer_key", side_effect=lambda *values, **options: transfers.append(values[2])), \
                patch.object(harness.subprocess, "run", side_effect=reply), \
                patch.object(harness.subprocess, "Popen", side_effect=start), \
                patch.object(harness.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
            return call(), transfers, calls

    def fake_run(self, temporary, environment, device_report, live=False):
        args = harness.argparse.Namespace(scenario=3, repetition=1, live=live, credential_check=not live, seconds=1,
                                          adb="adb", serial="emulator-5564", output=Path(temporary) / "out")
        def call():
            try:
                harness.run(args)
                return "passed"
            except SystemExit:
                return "failed"
        outcome, transfers, calls = self.fake_device(temporary, environment, device_report, call)
        manifest = json.loads(next(args.output.glob("*/*/manifest.json")).read_text())
        return outcome, transfers, calls, manifest

    def test_credential_check_sends_the_selected_provider_and_records_what_the_device_used(self):
        with tempfile.TemporaryDirectory() as temporary:
            outcome, transfers, calls, manifest = self.fake_run(
                temporary, {"ALPHY_AI_PROVIDER": "anthropic", "ALPHY_AI_MODEL": "claude-opus-5-5"},
                {"phase": "complete", "provider": "anthropic", "model": "claude-opus-5-5"})
        self.assertEqual("passed", outcome)
        self.assertEqual("anthropic", transfers[0][0])
        self.assertTrue(transfers[0][1].startswith("synthetic-credential-"))
        command = manifest["command"]
        self.assertEqual("claude-opus-5-5", command[command.index("aiModel") + 1])
        self.assertEqual(("anthropic", "claude-opus-5-5"), (manifest["provider"], manifest["model"]))
        self.assertNotIn(transfers[0][1], json.dumps([command for command, _ in calls]))

    def test_credential_check_defaults_to_deepseek_without_a_model(self):
        with tempfile.TemporaryDirectory() as temporary:
            outcome, transfers, _, manifest = self.fake_run(
                temporary, {}, {"phase": "complete", "provider": "deepseek", "model": "deepseek-flash"})
        self.assertEqual("passed", outcome)
        self.assertEqual("deepseek", transfers[0][0])
        self.assertNotIn("aiModel", manifest["command"])
        self.assertEqual(("deepseek", "deepseek-flash"), (manifest["provider"], manifest["model"]))

    def test_run_fails_when_the_device_reports_another_provider(self):
        with tempfile.TemporaryDirectory() as temporary:
            outcome, _, _, manifest = self.fake_run(
                temporary, {"ALPHY_AI_PROVIDER": "openai"}, {"phase": "complete", "provider": "deepseek", "model": "deepseek-flash"})
        self.assertEqual("failed", outcome)
        self.assertEqual("deepseek", manifest["provider"])

    def test_live_run_sends_the_account_and_gives_no_child_the_key(self):
        sentinel = "synthetic-credential-live"
        with tempfile.TemporaryDirectory() as temporary:
            outcome, transfers, calls, manifest = self.fake_run(
                temporary, {"ALPHY_AI_PROVIDER": "anthropic", "ANTHROPIC_API_KEY": sentinel, "OPENAI_API_KEY": "other"},
                {"phase": "complete", "provider": "anthropic", "model": "claude-sonnet-5-5"}, live=True)
        self.assertEqual("passed", outcome)
        self.assertEqual([("anthropic", sentinel)], transfers)
        self.assertNotIn(sentinel, json.dumps([command for command, _ in calls]))
        self.assertTrue(calls and all(name not in options["env"] for _, options in calls for name in self.KEY_NAMES))
        self.assertEqual("real", manifest["mode"])

    def test_live_run_without_a_known_provider_stops_before_any_command(self):
        for environment in ({}, {"ALPHY_AI_PROVIDER": "gemini", "DEEPSEEK_API_KEY": "synthetic-credential-key"}):
            with self.subTest(environment=environment), tempfile.TemporaryDirectory() as temporary:
                args = harness.argparse.Namespace(scenario=3, repetition=1, live=True, credential_check=False, seconds=1,
                                                  adb="adb", serial="emulator-5564", output=Path(temporary) / "out")
                with patch.dict(harness.os.environ, environment, clear=True), \
                        patch.object(harness.Path, "home", return_value=Path(temporary)), \
                        patch.object(harness.subprocess, "run") as execute, \
                        self.assertRaisesRegex(RuntimeError, "ALPHY_AI_PROVIDER"):
                    harness.run(args)
                execute.assert_not_called()

    LIVE_VOICE = "com.alpha.assistant.voice.LiveVoiceInputTest#transcribeRussianFixture"

    def fake_live_check(self, temporary, device_report, environment, test=LIVE_VOICE):
        args = harness.argparse.Namespace(serial="emulator-5564", label="live", test=test, adb="adb", output=Path(temporary) / "out")
        code, transfers, calls = self.fake_device(temporary, environment, device_report, lambda: harness.live_check(args))
        return code, transfers, calls, next((args.output / "live").iterdir())

    def test_live_check_transfers_the_account_and_records_what_the_device_used(self):
        sentinel = "synthetic-credential-live-check"
        report = {"phase": "complete", "provider": "openai", "model": "gpt-transcribe", "text": "Поставь таймер на пять минут"}
        with tempfile.TemporaryDirectory() as temporary:
            code, transfers, calls, directory = self.fake_live_check(
                temporary, report, {"ALPHY_AI_PROVIDER": "openai", "OPENAI_API_KEY": sentinel, "ALPHY_AI_MODEL": "gpt-6.1-sol"})
            manifest = json.loads((directory / "manifest.json").read_text())
            evidence = b"".join(path.read_bytes() for path in directory.iterdir())
        self.assertEqual(0, code)
        self.assertEqual([("openai", sentinel)], transfers)
        command = manifest["command"]
        self.assertEqual(self.LIVE_VOICE, command[command.index("class") + 1])
        self.assertEqual(directory.name, command[command.index("liveRunId") + 1])
        self.assertEqual("gpt-6.1-sol", command[command.index("aiModel") + 1])
        self.assertEqual(("passed", "openai", "gpt-transcribe"), (manifest["status"], manifest["provider"], manifest["model"]))
        self.assertNotIn(sentinel.encode(), evidence)
        self.assertNotIn(sentinel, json.dumps([command for command, _ in calls]))
        self.assertTrue(all(name not in options["env"] for _, options in calls for name in self.KEY_NAMES))

    def test_live_check_fails_a_skipped_test_and_another_provider(self):
        for report in ({"phase": "skipped", "provider": "anthropic", "model": None},
                       {"phase": "complete", "provider": "deepseek", "model": None}):
            with self.subTest(report=report), tempfile.TemporaryDirectory() as temporary:
                code, _, _, directory = self.fake_live_check(
                    temporary, report, {"ALPHY_AI_PROVIDER": "anthropic", "ANTHROPIC_API_KEY": "synthetic-credential-key"})
                self.assertEqual(1, code)
                self.assertEqual("failed", json.loads((directory / "manifest.json").read_text())["status"])

    def test_live_check_accepts_only_live_tests_of_the_app(self):
        allowed = (self.LIVE_VOICE, "com.alpha.assistant.courses.LiveCourseWorkflowTest#generateAndPlay",
                   "com.alpha.assistant.LiveCheck#run_1")
        refused = ("com.alpha.assistant.tools.OneUseCredentialTest#receive", "org.example.LiveTest#run",
                   "com.alpha.assistantx.LiveTest#run", "com.alpha.assistant.voice.liveVoiceInputTest#run",
                   "com.alpha.assistant.Live.Other#run", "com.alpha.assistant.courses.LiveCourseWorkflowTest",
                   "com.alpha.assistant.voice.LiveVoiceInputTest", "com.alpha.assistant.voice.LiveVoiceInputTest#",
                   self.LIVE_VOICE + " -e aiModel x", self.LIVE_VOICE + "$x", self.LIVE_VOICE + ",com.alpha.assistant.LiveOther")
        for test in allowed:
            self.assertTrue(harness.live_test(test), test)
        for test in refused:
            with self.subTest(test=test), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(harness.subprocess, "run") as execute, patch.object(harness.subprocess, "Popen") as start:
                self.assertFalse(harness.live_test(test))
                args = harness.argparse.Namespace(serial="emulator-5564", label="live", test=test, adb="adb", output=Path(temporary))
                with self.assertRaisesRegex(SystemExit, "Live"):
                    harness.live_check(args)
                execute.assert_not_called()
                start.assert_not_called()
                self.assertEqual([], list(Path(temporary).iterdir()))

    def test_run_uses_the_selected_serial_for_every_adb_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = harness.argparse.Namespace(scenario=3, repetition=1, live=False, credential_check=True, seconds=1,
                                              adb="adb", serial="emulator-5564", output=Path(temporary))
            identity = {"revision": "a" * 40, "sourceSha256": "b" * 64}
            process = Mock(**{"poll.return_value": 0})
            def reply(command, **options):
                return Mock(stdout=self.appsearch_dump(0) if command[-1] == "app_search" else "", stderr="", returncode=int(command[-1] != "app_search"))
            with patch.dict(harness.os.environ, {}, clear=True), patch.object(harness.Path, "home", return_value=Path(temporary)), \
                    patch.object(harness, "identity", return_value=identity), \
                    patch.object(harness.subprocess, "run", side_effect=reply) as execute, \
                    patch.object(harness.subprocess, "Popen", return_value=process) as start, \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
                harness.run(args)
            commands = [call.args[0] for call in execute.call_args_list] + [call.args[0] for call in start.call_args_list]
            adb = [command for command in commands if command[0] == "adb"]
            self.assertTrue(adb)
            self.assertTrue(all(command[1:3] == ["-s", "emulator-5564"] for command in adb), adb)
            manifest = json.loads(next(Path(temporary).glob("*/*/manifest.json")).read_text())
            self.assertEqual("emulator-5564", manifest["serial"])
            self.assertEqual(0, manifest["semanticChunkTypes"])
            self.assertIn("emulator-5564", manifest["command"])

    def test_run_requires_a_serial(self):
        with patch.object(sys, "argv", ["run.py", "run", "--scenario", "3", "--repetition", "1"]), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            harness.main()

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
                "ro.build.fingerprint": "fixture/sdk/29", "webviewupdate": "WebView 133 fixture",
                "app_search": HarnessBoundaries.appsearch_dump(0)}.get(command[-1], "")

    @staticmethod
    def appsearch_dump(chunks, other=1):
        """The schema part of dumpsys app_search: types print with 2 spaces, their properties with 4."""
        def schema_type(names):
            properties = "".join(f'    properties {{\n      cardinality: OPTIONAL\n      property_name: "{name}"\n    }}\n' for name in names)
            return f'  types {{\n    description: ""\n{properties}    schema_type: "HASH"\n    version: 1\n  }}\n'
        chunk = ["source", "revision", "signature", "start", "end", "vector"]
        return "crc: 1\nschema {\n" + schema_type(chunk) * chunks + schema_type(chunk[:-1]) * other + schema_type(chunk + ["extra"]) * other + "}\n"

    def test_semantic_chunk_count_reads_only_chunk_shaped_types(self):
        self.assertEqual(3, harness.semantic_chunk_count(self.appsearch_dump(3, other=2)))
        self.assertEqual(0, harness.semantic_chunk_count(self.appsearch_dump(0)))
        self.assertIsNone(harness.semantic_chunk_count(""))

    def test_semantic_store_error_stops_only_above_the_limit(self):
        limit = harness.SEMANTIC_CHUNK_LIMIT
        self.assertIsNone(harness.semantic_store_error(0))
        self.assertIsNone(harness.semantic_store_error(limit))
        self.assertIn(str(limit + 1), harness.semantic_store_error(limit + 1))
        self.assertIsNone(harness.semantic_store_error(None))

    def test_suite_stops_with_an_environment_error_only_when_the_count_is_above_the_limit(self):
        limit = harness.SEMANTIC_CHUNK_LIMIT
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for label, dump in (("below", self.appsearch_dump(3)), ("at-limit", self.appsearch_dump(limit))):
                code, observed, _ = self.fake_suite(output, label, dump=dump)
                self.assertEqual((0, "passed"), (code, observed))
                record = json.loads((output / label / "run.json").read_text())
                self.assertEqual(int(label == "at-limit") * limit or 3, record["semanticChunkTypes"])
                self.assertIsNone(record["semanticChunkNote"])
            code, observed, calls = self.fake_suite(output, "over-limit", dump=self.appsearch_dump(limit + 1))
            record = json.loads((output / "over-limit/run.json").read_text())
            self.assertEqual((1, "environment-error"), (code, observed))
            self.assertEqual((0, limit + 1), (record["tests"], record["semanticChunkTypes"]))
            self.assertIn("AppSearch", record["deviceError"])
            self.assertFalse(any(c[0] == "./gradlew" or "install" in c for c, _ in calls))

    def test_suite_continues_and_records_an_unknown_count_when_the_dump_gives_none(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for label, options in (("empty", {"dump": ""}), ("unparseable", {"dump": "Service not found"}),
                                   ("failed-command", {"failed_command": "app_search"})):
                code, observed, calls = self.fake_suite(output, label, **options)
                record = json.loads((output / label / "run.json").read_text())
                self.assertEqual((0, "passed"), (code, observed))
                self.assertIsNone(record["semanticChunkTypes"])
                self.assertIn("no readable", record["semanticChunkNote"])
                self.assertTrue(any(c[0] == "./gradlew" for c, _ in calls))

    def test_run_continues_with_an_unknown_count_and_stops_above_the_limit(self):
        for dump, stops in ((None, False), (self.appsearch_dump(harness.SEMANTIC_CHUNK_LIMIT + 1), True)):
            with tempfile.TemporaryDirectory() as temporary:
                args = harness.argparse.Namespace(scenario=3, repetition=1, live=False, credential_check=True, seconds=1,
                                                  adb="adb", serial="emulator-5564", output=Path(temporary))
                def reply(command, **options):
                    if command[-1] == "app_search":
                        return Mock(stdout=dump or "", stderr="", returncode=int(dump is None))
                    return Mock(stdout="", stderr="", returncode=1)
                with patch.dict(harness.os.environ, {}, clear=True), patch.object(harness.Path, "home", return_value=Path(temporary)), \
                        patch.object(harness, "identity", return_value={"revision": "a" * 40, "sourceSha256": "b" * 64}), \
                        patch.object(harness.subprocess, "run", side_effect=reply), \
                        patch.object(harness.subprocess, "Popen", return_value=Mock(**{"poll.return_value": 0})), \
                        contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit):
                    harness.run(args)
                manifests = list(Path(temporary).glob("*/*/manifest.json"))
                self.assertEqual(not stops, bool(manifests))
                if manifests:
                    manifest = json.loads(manifests[0].read_text())
                    self.assertIsNone(manifest["semanticChunkTypes"])
                    self.assertIn("no readable", manifest["semanticChunkNote"])

    def fake_suite(self, output, label, instrumentation=SUITE_PASSED, installed="f" * 64, test_install=0, baseline=None,
                   *, serial="emulator-5560", emulator=True, installed_test="f" * 64, failed_command=None, dump=None):
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
            if command[-1] == "app_search" and dump is not None:
                reply = dump
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
                patch.dict(harness.os.environ, {**environment, **{name: "test-value" for name in self.KEY_NAMES}}, clear=True):
            _, observed, calls = self.fake_suite(Path(temporary), "environment")
        self.assertEqual("passed", observed)
        self.assertEqual([], [command for command, options in calls if set(self.KEY_NAMES) & options["env"].keys()])
        self.assertNotIn("test-value", json.dumps([command for command, _ in calls]))
        build_paths = [(command[0], options["env"].get("JAVA_HOME"), options["env"].get("ANDROID_HOME"))
                       for command, options in calls if {"JAVA_HOME", "ANDROID_HOME"} & options["env"].keys()]
        self.assertEqual([("./gradlew", "/opt/homebrew/opt/openjdk@17", "/opt/homebrew/share/android-commandlinetools")], build_paths)


if __name__ == "__main__":
    unittest.main()
