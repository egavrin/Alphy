"""Offline checks for frozen input, transport, and capture identity failures."""
import base64
import json
import unittest

from android_capture import PROVIDER, ROUTE, envelope, instrument_command, load_slot, match_report
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


if __name__ == "__main__":
    unittest.main()
