#!/usr/bin/env python3
"""Check sequential slot selection without a device or provider."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from compare import digest, encode, make_plan
from run_functional import load_slots, slot_states


class FunctionalRunnerTest(unittest.TestCase):
    def test_tuning_plan_cannot_schedule_a_held_out_request(self):
        execution = {"products": {"candidate": {}, "reference": {}}}
        cases = [{"id": "fresh:private", "request": "Fixture", "split": "held-out"}]
        plan = make_plan(cases, execution, {}, [], "tuning")
        with TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_bytes(encode(plan))
            with self.assertRaisesRegex(ValueError, "Held-out"):
                load_slots(path, digest(path.read_bytes()))

    def test_existing_slots_keep_their_evidence(self):
        slots = dict.fromkeys(("device-complete", "device-failed", "host-failed", "host-started", "next"))
        captures = {
            "device-complete": ({"kind": "android", "capture_status": "device-complete"}, None),
            "device-failed": ({"kind": "android", "capture_status": "capture-failed"}, None),
            "host-failed": ({"kind": "reference-host", "attempts": [{"status": "complete", "host_outcome": "failed"}]}, None),
            "host-started": ({"kind": "reference-host", "attempts": [{"status": "started"}]}, None),
        }
        self.assertEqual(slot_states(slots, captures), {
            "device-complete": "complete", "device-failed": "interrupted",
            "host-failed": "complete", "host-started": "interrupted", "next": "pending",
        })


if __name__ == "__main__":
    unittest.main()
