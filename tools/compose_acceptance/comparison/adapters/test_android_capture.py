"""Offline checks for frozen input, transport, and capture identity failures."""
import argparse
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from android_capture import PROVIDER, ROUTE, capture, envelope, instrument_command, load_slot, match_report
from compare import digest, encode


class CaptureProtocolTest(unittest.TestCase):
    def setUp(self):
        self.request = 'Сделай игру: "мяч"; $(echo ignored)\n⚽'
        self.product = {"revision": "1" * 40, "source_sha256": "2" * 64, "build_sha256": "3" * 64,
                        "route": ROUTE, "provider": PROVIDER, "device": {"model": "test", "os": "test", "config": "test"}}
        self.slot = {"id": "extra:game:candidate:3", "case_id": "extra:game", "product": "candidate", "repeat": 3,
                     "request_sha256": digest(self.request.encode()), "execution_sha256": digest(encode(self.product))}
        self.plan = {"format": 1, "purpose": "final", "execution": {"products": {"candidate": self.product}},
                     "cases": [{"id": self.slot["case_id"], "request": self.request, "split": "held-out"}],
                     "slots": [self.slot]}

    def load(self):
        raw = encode(self.plan)
        return load_slot(raw, digest(raw), self.slot["id"])

    def test_preserves_the_exact_request_without_shell_metacharacters(self):
        product, slot, case = self.load()
        payload = envelope(product, slot, case, digest(encode(self.plan)), "a" * 32, 5)
        command = instrument_command("adb", payload)
        argument = command[command.index("comparisonEnvelope") + 1]
        self.assertRegex(argument, r"^[A-Za-z0-9_-]+$")
        self.assertEqual(json.loads(base64.urlsafe_b64decode(argument + "=" * (-len(argument) % 4))), payload)
        self.assertEqual(payload["request"], self.request)
        self.assertNotIn(self.request, command)

    def test_passes_the_selected_model_only_when_set(self):
        product, slot, case = self.load()
        payload = envelope(product, slot, case, digest(encode(self.plan)), "a" * 32, 5)
        self.assertNotIn("aiModel", instrument_command("adb", payload))
        command = instrument_command("adb", payload, "claude-opus-5-5")
        index = command.index("aiModel")
        self.assertEqual(["-e", "aiModel", "claude-opus-5-5"], command[index - 1:index + 2])
        self.assertTrue(command[-1].endswith("AndroidJUnitRunner"))

    def test_rejects_changes_and_wrong_slot_kind(self):
        with self.assertRaises(ValueError):
            load_slot(encode(self.plan), "f" * 64, self.slot["id"])
        for key, value in (("request_sha256", "a" * 64), ("execution_sha256", "b" * 64),
                           ("product", "reference"), ("repeat", True)):
            original = self.slot[key]
            self.slot[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.load()
            self.slot[key] = original

    def test_tuning_does_not_accept_a_held_out_request(self):
        self.plan["purpose"] = "tuning"
        with self.assertRaises(ValueError):
            self.load()

    def test_report_requires_exact_echo_before_credential_transfer(self):
        product, slot, case = self.load()
        payload = envelope(product, slot, case, digest(encode(self.plan)), "a" * 32, 5)
        report = {key: value for key, value in payload.items() if key not in ("request", "review_seconds")}
        report["phase"] = "credentialReady"
        match_report(report, payload)
        for key in ("run_id", "slot_id", "execution_sha256", "request_sha256", "plan_sha256", "build_sha256"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                match_report({**report, key: "changed"}, payload)

    def run_capture(self, provider, report):
        """One offline capture() with a synthetic account and a device that writes [report] when its run ends."""
        apks = {"com.alpha.assistant": "4" * 64}
        self.product["build_sha256"] = digest(encode(apks))
        self.slot["execution_sha256"] = digest(encode(self.product))
        raw = encode(self.plan)

        def adb(_adb, *command):
            if command[:2] != ("shell", "cat"):
                return ""
            run_id = command[2].split("/")[-2]
            return json.dumps({"run_id": run_id, "slot_id": self.slot["id"], "plan_sha256": digest(raw),
                               "request_sha256": self.slot["request_sha256"],
                               "execution_sha256": self.slot["execution_sha256"],
                               "revision": self.product["revision"], "source_sha256": self.product["source_sha256"],
                               "build_sha256": self.product["build_sha256"], **report})

        with tempfile.TemporaryDirectory() as temporary:
            plan = Path(temporary) / "plan.json"
            plan.write_bytes(raw)
            args = argparse.Namespace(plan=plan, plan_sha256=digest(raw), slot=self.slot["id"], review_seconds=5,
                                      adb="adb", output=Path(temporary) / "capture", verify_tip=False)
            with patch.dict(os.environ, {"ALPHY_AI_PROVIDER": provider}), \
                    patch("pathlib.Path.home", return_value=Path(temporary)), \
                    patch("android_capture.identity", return_value={"revision": self.product["revision"],
                                                                    "sourceSha256": self.product["source_sha256"]}), \
                    patch("android_capture.installed_build", return_value=apks), \
                    patch("android_capture.device_identity", return_value=self.product["device"]), \
                    patch("android_capture.read_live_account", return_value=(provider, "synthetic")) as read, \
                    patch("android_capture.transfer_key"), patch("android_capture.adb_output", side_effect=adb), \
                    patch("subprocess.Popen", return_value=Mock(poll=Mock(return_value=0))) as spawn, \
                    contextlib.redirect_stdout(io.StringIO()):
                code = capture(args)
            return code, json.loads((args.output / "capture.json").read_bytes()), read, spawn

    def test_refuses_another_provider_before_the_key_read_and_the_device_run(self):
        for provider in ("openai", ""):
            with self.subTest(provider=provider):
                code, manifest, read, spawn = self.run_capture(provider, {"phase": "complete"})
                self.assertEqual((1, "capture-failed"), (code, manifest["status"]))
                self.assertIn("ALPHY_AI_PROVIDER=deepseek", manifest["failure"])
                read.assert_not_called()
                spawn.assert_not_called()

    def test_fails_a_report_from_another_provider_or_model(self):
        frozen = {"phase": "complete", "provider": "deepseek", "model": PROVIDER["model"]}
        for report, status in ((frozen, "device-complete"), ({**frozen, "provider": "openai"}, "capture-failed"),
                               ({**frozen, "model": "deepseek-pro"}, "capture-failed"),
                               ({"phase": "failed"}, "device-failed")):
            with self.subTest(report=report):
                code, manifest, _read, _spawn = self.run_capture("deepseek", report)
                self.assertEqual((0 if status == "device-complete" else 1, status), (code, manifest["status"]))


if __name__ == "__main__":
    unittest.main()
