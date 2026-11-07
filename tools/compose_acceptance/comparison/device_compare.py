#!/usr/bin/env python3
"""Freeze and audit physical workflows separately from the frozen host matrix."""
import argparse
import json
from pathlib import Path

from compare import (PRODUCTS, android_packet, digest, encode, evidence_path, functional_capture, hex_id,
                     host_packet, integer, load_final_plan, nonempty, read_json, require)


def make_manifest(plan, plan_hash, criteria, products):
    require(set(products) == set(PRODUCTS), "Freeze both physical product identities.")
    for name, product in products.items():
        require(hex_id(product["revision"], 40) and all(hex_id(product[key], 64) for key in ("source_sha256", "build_sha256"))
                and nonempty(product["signing"]) and nonempty(product["route"]), "Pin device source, build, signing, and route.")
        require(product["revision"] == plan["execution"]["products"][name].get("revision", product["revision"]),
                "Use the same source revision for host and device runs.")
        device = product["device"]
        require(device["kind"] == "physical" and all(nonempty(device[key]) for key in ("model", "os", "config")),
                "Freeze a physical device identity.")
    cases = {case["id"]: case for case in plan["cases"]}
    required = criteria["cases"]
    require(isinstance(required, dict) and required and required.keys() <= cases.keys(), "Use frozen case IDs for device criteria.")
    for case_id, case in required.items():
        checks = case["checks"]
        require(isinstance(checks, dict) and checks and checks.keys() <= cases[case_id]["interactions"].keys(),
                "Bind device criteria to frozen interaction IDs.")
        require(all(row["kind"] in {"readback", "physical"} and nonempty(row["expected"]) for row in checks.values()),
                "Freeze the expected readback or physical measurement for every device check.")
    slots = [{"slot_id": slot["id"] + ":device", "host_slot_id": slot["id"], "case_id": slot["case_id"],
              "product": slot["product"], "repeat": slot["repeat"], "request_sha256": slot["request_sha256"],
              "execution_sha256": digest(encode(products[slot["product"]]))}
             for slot in plan["slots"] if slot["case_id"] in required]
    return {"format": 1, "host_plan_sha256": plan_hash, "products": products, "cases": required, "slots": slots}


def file_evidence(root, ref):
    return evidence_path(root, ref["path"], ref["sha256"], "device evidence")


def retained_host_artifact(record, plan, root):
    generation = record["generation"]
    capture = generation["host_capture"]
    path = file_evidence(root, capture)
    slots = {slot["id"]: slot for slot in plan["slots"] if slot["id"] == record["host_slot_id"]}
    products = plan["execution"]["products"]
    plan_hash = record["host_plan_sha256"]
    if record["product"] == "candidate":
        require(path.name == "capture.json", "Deployment requires the terminal Android capture manifest.")
        directory = path.parent
        _, summary, data = android_packet(directory, root, slots, products, plan_hash)
    else:
        require(path.name == "result.json" and path.parent.name in {"attempt-1", "attempt-2"},
                "Deployment requires a terminal reference host result.")
        directory = path.parent.parent
        _, summary, data = host_packet(directory, slots, products, plan_hash)
    summary["directory"] = str(directory.relative_to(root))
    artifact, _, _ = functional_capture((summary, data), {"outcome": "generated", "capture_sha256": capture["sha256"]}, root)
    if record["product"] == "candidate":
        report, files = data
        sha = files["artifact.json"]
        definition = read_json(evidence_path(directory, "artifact.json", sha, "retained Android artifact"))
        require(sha == report["artifact_sha256"] and definition["artifact_id"] == artifact and
                definition["artifact_revision"] == report["artifact_revision"], "The Android artifact differs from the host capture.")
        return sha
    return artifact


def audit_records(manifest, manifest_hash, records, root, plan):
    slots = {slot["slot_id"]: slot for slot in manifest["slots"]}
    seen, runs, used_measurements, passed, valid, invalid = set(), set(), set(), set(), set(), []
    for line, record in enumerate(records, 1):
        try:
            slot = slots[record["slot_id"]]
            require(slot["slot_id"] not in seen, "The device slot already has a record.")
            seen.add(slot["slot_id"])
            require(all(record[key] == value for key, value in slot.items()) and
                    record["device_manifest_sha256"] == manifest_hash and
                    record["host_plan_sha256"] == manifest["host_plan_sha256"], "Device record differs from its frozen lineage.")
            product = manifest["products"][slot["product"]]
            require(record["device"] == product["device"] and record["device"]["kind"] == "physical" and
                    record["mode"] == "live" and hex_id(record["run_id"], 32) and record["run_id"] not in runs,
                    "Use one unique real physical execution per device slot.")
            runs.add(record["run_id"])
            require(record["status"] == "complete" and record["outcome"] in {"generated", "failed", "refused"},
                    "Keep a terminal device outcome.")
            generation = record["generation"]
            require(generation["kind"] in {"fresh", "deployed"}, "Record fresh generation or exact artifact deployment.")
            trace = read_json(file_evidence(root, generation["trace"]))
            artifact_sha = record["artifact"]["sha256"] if record["outcome"] == "generated" else None
            deployed_sha = generation["host_artifact_sha256"] if generation["kind"] == "deployed" else None
            require(all(trace[key] == record[key] for key in ("slot_id", "run_id", "request_sha256", "execution_sha256",
                                                             "device_manifest_sha256")) and trace["artifact_sha256"] == (deployed_sha or artifact_sha),
                    "The native trace differs from the device execution and artifact.")
            events = trace["events"]
            require(isinstance(events, list) and events and all(nonempty(event["kind"]) for event in events), "Retain native trace events.")
            if generation["kind"] == "fresh":
                calls = generation["provider_http_calls"]
                require(integer(calls, 1) and sum(event["kind"] == "provider-dispatch" for event in events) == calls,
                        "Fresh device generation needs matching provider dispatch evidence.")
                require(record["product"] != "candidate" or artifact_sha is None,
                        "Exercise the retained Android artifact from the host matrix.")
            else:
                require(hex_id(deployed_sha, 64) and (artifact_sha is None or artifact_sha == deployed_sha) and
                        retained_host_artifact(record, plan, root) == deployed_sha, "Deploy the exact retained host artifact.")
            checks = record["checks"]
            expected = manifest["cases"][slot["case_id"]]["checks"]
            if record["outcome"] == "generated":
                file_evidence(root, record["artifact"])
                require(checks.keys() == expected.keys(), "Review every frozen device check.")
                measurements = set()
                reserved = {artifact_sha, generation["trace"]["sha256"], generation.get("host_capture", {}).get("sha256")}
                for check in checks.values():
                    require(check["status"] in {"pass", "fail", "unavailable"} and nonempty(check["observed"]),
                            "Record the observed device result.")
                    require(isinstance(check["evidence"], list) and check["evidence"], "Retain native readback or physical measurements.")
                    for ref in check["evidence"]:
                        file_evidence(root, ref)
                        require(ref["sha256"] not in used_measurements and ref["sha256"] not in reserved,
                                "Device measurements must belong to this execution and have a distinct evidence role.")
                        measurements.add(ref["sha256"])
                used_measurements.update(measurements)
                if all(check["status"] == "pass" for check in checks.values()):
                    passed.add((slot["case_id"], slot["product"]))
            else:
                require(not checks and nonempty(record["failure"]), "Record a failed device workflow without a passing check.")
            valid.add(slot["slot_id"])
        except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
            invalid.append({"line": line, "reason": str(error)})
    missing = sorted(slots.keys() - valid)
    uncovered = [f"{case}/{product}" for case in manifest["cases"] for product in PRODUCTS if (case, product) not in passed]
    return {"gate": "blocked" if missing or invalid or uncovered else "pass", "device_manifest_sha256": manifest_hash,
            "planned_slots": len(slots), "valid_records": len(valid), "missing_slots": missing,
            "uncovered_workflows": uncovered, "invalid_records": invalid}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "audit"))
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--plan-sha256", required=True)
    parser.add_argument("--criteria", type=Path)
    parser.add_argument("--products", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--manifest-sha256")
    parser.add_argument("--results", type=Path)
    parser.add_argument("--evidence-dir", type=Path)
    args = parser.parse_args()
    try:
        plan = load_final_plan(args)
        if args.command == "prepare":
            manifest = make_manifest(plan, args.plan_sha256, read_json(args.criteria), read_json(args.products))
            args.out.mkdir(mode=0o700)
            raw = encode(manifest)
            (args.out / "device-manifest.json").write_bytes(raw)
            result = {"device_manifest_sha256": digest(raw), "slots": len(manifest["slots"])}
        else:
            raw = args.manifest.read_bytes()
            require(digest(raw) == args.manifest_sha256, "The device manifest differs from its frozen hash.")
            manifest = json.loads(raw)
            expected = make_manifest(plan, args.plan_sha256, {"cases": manifest["cases"]}, manifest["products"])
            require(manifest == expected, "Device manifest differs from its host plan.")
            records = [json.loads(line) for line in args.results.read_text().splitlines() if line.strip()]
            result = audit_records(manifest, args.manifest_sha256, records, args.evidence_dir.resolve(), plan)
    except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
        parser.exit(2, f"{error}\n")
    print(encode(result).decode(), end="")
    return int(result.get("gate") == "blocked")


if __name__ == "__main__":
    raise SystemExit(main())
