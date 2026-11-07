#!/usr/bin/env python3
"""Offline device-audit fixtures never establish a physical-device pass."""
import copy
from pathlib import Path
import tempfile
import unittest

import compare
import device_compare


class DeviceBoundary(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="alphy-device-audit-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.products = {name: {"revision": "1" * 40, "source_sha256": "2" * 64, "build_sha256": "3" * 64,
                               "signing": "fixture", "route": name + "-physical",
                               "device": {"kind": "physical", "model": "fixture", "os": "fixture", "config": "fixture"}}
                         for name in compare.PRODUCTS}
        cases = [{"id": "fixture", "request": "Fixture", "interactions": {"effect": "Observe the effect."}}]
        self.runtime = {"fixture": True}
        reference = {"revision": "1" * 40, "source_sha256": "2" * 64,
                     "build_sha256": compare.digest(compare.encode(self.runtime)),
                     "route": compare.HOST_ROUTE, "provider": {"name": "fixture"}}
        self.plan = compare.make_plan(cases, {"products": {"candidate": {}, "reference": reference}}, {}, [], "final")
        self.criteria = {"cases": {"fixture": {"checks": {"effect": {"kind": "physical", "expected": "One pulse."}}}}}
        self.manifest = device_compare.make_manifest(self.plan, "4" * 64, self.criteria, self.products)
        self.slot = next(slot for slot in self.manifest["slots"] if slot["product"] == "reference")
        (self.root / "recording.bin").write_bytes(b"fixture measurement")
        evidence = {"path": "recording.bin", "sha256": compare.digest(b"fixture measurement")}
        artifact = b"fixture artifact"
        (self.root / "program.vera").write_bytes(artifact)
        self.record = {**self.slot, "device_manifest_sha256": compare.digest(compare.encode(self.manifest)),
                       "host_plan_sha256": "4" * 64, "mode": "live", "run_id": "5" * 32, "status": "complete",
                       "outcome": "generated", "artifact": {"path": "program.vera", "sha256": compare.digest(artifact)},
                       "generation": {"kind": "fresh",
                       "provider_http_calls": 1}, "device": self.products[self.slot["product"]]["device"],
                       "checks": {"effect": {"status": "pass", "observed": "Measured one pulse.", "evidence": [evidence]}}}
        self.trace(self.record)

    def trace(self, record):
        fields = ("slot_id", "run_id", "execution_sha256", "device_manifest_sha256", "request_sha256")
        trace = {key: record[key] for key in fields}
        generation = record["generation"]
        trace.update(artifact_sha256=generation.get("host_artifact_sha256") or record["artifact"]["sha256"],
                     events=[{"kind": "provider-dispatch" if generation["kind"] == "fresh" else "deployment"}])
        raw = compare.encode(trace)
        path = record["run_id"] + ".json"
        (self.root / path).write_bytes(raw)
        record["generation"]["trace"] = {"path": path, "sha256": compare.digest(raw)}

    def audit(self, record=None):
        return device_compare.audit_records(self.manifest, compare.digest(compare.encode(self.manifest)),
                                            [record or self.record], self.root, self.plan)

    def test_host_slots_are_unchanged_by_device_criteria(self):
        plan = copy.deepcopy(self.plan)
        plan["cases"][0]["device_checks"] = {"effect": "physical"}
        self.assertEqual(compare.make_plan(plan["cases"], plan["execution"], {}, [], "final")["slots"], self.plan["slots"])
        self.assertEqual(len(self.manifest["slots"]), 6)
        self.assertTrue(all(slot["host_slot_id"] in {s["id"] for s in self.plan["slots"]} for slot in self.manifest["slots"]))

    def test_partial_device_evidence_stays_blocked(self):
        result = self.audit()
        self.assertEqual(result["valid_records"], 1)
        self.assertEqual(result["gate"], "blocked")
        self.assertEqual(result["invalid_records"], [])

    def test_emulator_is_rejected(self):
        record = copy.deepcopy(self.record)
        record["device"]["kind"] = "emulator"
        self.assertTrue(self.audit(record)["invalid_records"])

    def test_replaced_recording_is_rejected(self):
        (self.root / "recording.bin").write_bytes(b"replacement")
        self.assertTrue(self.audit()["invalid_records"])

    def test_wrong_host_lineage_is_rejected(self):
        record = {**self.record, "host_plan_sha256": "f" * 64}
        self.assertTrue(self.audit(record)["invalid_records"])

    def test_synthetic_or_missing_measurements_are_rejected(self):
        self.assertTrue(self.audit({**self.record, "mode": "synthetic"})["invalid_records"])
        record = copy.deepcopy(self.record)
        record["checks"]["effect"]["evidence"] = []
        self.assertTrue(self.audit(record)["invalid_records"])

    def test_duplicate_execution_cannot_supply_another_repeat(self):
        other = {**self.record, **self.manifest["slots"][-1]}
        result = device_compare.audit_records(self.manifest, compare.digest(compare.encode(self.manifest)),
                                             [self.record, other], self.root, self.plan)
        self.assertTrue(result["invalid_records"])

    def test_new_run_id_cannot_reuse_a_measurement(self):
        other = {**copy.deepcopy(self.record), **self.manifest["slots"][-1], "run_id": "6" * 32}
        self.trace(other)
        result = device_compare.audit_records(self.manifest, compare.digest(compare.encode(self.manifest)),
                                             [self.record, other], self.root, self.plan)
        self.assertTrue(result["invalid_records"])

    def test_text_file_cannot_supply_a_deployed_host_artifact(self):
        record = copy.deepcopy(self.record)
        record["generation"].update(kind="deployed", host_artifact_sha256=record["artifact"]["sha256"],
                                    host_capture=record["artifact"])
        self.assertTrue(self.audit(record)["invalid_records"])

    def test_failed_deployment_keeps_terminal_evidence_with_exact_host_lineage(self):
        host = next(slot for slot in self.plan["slots"] if slot["id"] == self.slot["host_slot_id"])
        attempt = self.root / "host/attempt-1"
        attempt.mkdir(parents=True)
        artifact = self.record["artifact"]
        (attempt / "program.vera").write_bytes((self.root / artifact["path"]).read_bytes())
        packet = {"format": 1, "slot_id": host["id"], "plan_sha256": "4" * 64,
                  "request_sha256": host["request_sha256"], "execution_sha256": host["execution_sha256"],
                  "source": self.plan["execution"]["products"]["reference"], "runtime": self.runtime,
                  "mode": "live", "origin": "reference-host", "attempt": 1, "generation_id": "7" * 32,
                  "attempt_id": "8" * 32, "status": "complete", "host_outcome": "built", "comparison_evidence": {},
                  "artifacts": {"program.vera": {"sha256": artifact["sha256"]}}}
        raw = compare.encode(packet)
        (attempt / "result.json").write_bytes(raw)
        record = {**self.record, "outcome": "failed", "failure": "Installation failed.", "checks": {}, "artifact": None,
                  "generation": {"kind": "deployed", "host_artifact_sha256": artifact["sha256"],
                                 "host_capture": {"path": "host/attempt-1/result.json", "sha256": compare.digest(raw)}}}
        self.trace(record)
        result = self.audit(record)
        self.assertEqual(result["valid_records"], 1)
        self.assertEqual(result["invalid_records"], [])
        self.assertEqual(result["gate"], "blocked")


if __name__ == "__main__":
    unittest.main()
