#!/usr/bin/env python3
"""Freeze the approved comparison corpus and audit recorded generations offline."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
from statistics import mean, median
import subprocess
import unicodedata


CORPUS_REVISION = "7ee06af1941729b00a110493ad9d1d9b5b127d17"
SOURCES = {
    "fresh": ("tools/vera-harness/compose-fresh.tsv", 95),
    "compose": ("tools/vera-harness/compose-prompts.tsv", 21),
    "bench": ("tools/vera-bench/prompts.tsv", 5),
}
COMPOSE_REFUSALS = {"weather", "notes", "alarm", "quiz", "mortgage", "dice"}
BENCH_CATEGORIES = {"tip": "money", "egg": "timer", "pill": "calendar",
                    "melody": "audio-torch", "shanghai": "navigation-calendar"}
BASELINE_GAMES = {"compose:quiz", "fresh:beyond-2", "fresh:beyond-5"}
PRODUCTS = ("candidate", "reference")
OUTCOMES = {"generated", "refused", "failed"}
STATE_KEYS = ("draft", "session", "store")
PERFORMANCE = ("latency", "frames")
ANDROID_ROUTE = "alphy-android-chat"
HOST_ROUTE = "vera-reference-host"
IDENTITY_KEYS = ("revision", "source_sha256", "build_sha256")
ANDROID_DEVICE_KEYS = ("model", "os", "fingerprint", "size", "density")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def hex_id(value, length):
    return isinstance(value, str) and re.fullmatch(rf"[0-9a-f]{{{length}}}", value) is not None


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def text_map(value):
    return isinstance(value, dict) and all(nonempty(key) and nonempty(item) for key, item in value.items())


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_bytes())


def git(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True,
                            env={**os.environ, "GIT_NO_LAZY_FETCH": "1", "GIT_TERMINAL_PROMPT": "0"})
    require(result.returncode == 0, f"Cannot read local Git snapshot: {' '.join(args)}")
    return result.stdout


def load_corpus(repo, revision):
    require(revision == CORPUS_REVISION, "The approved English corpus requires its exact source revision.")
    require(git(repo, "rev-parse", "--verify", revision + "^{commit}").decode().strip() == revision,
            "The corpus source must be a commit.")
    cases = []
    for group, (path, count) in SOURCES.items():
        raw = git(repo, "show", f"{revision}:{path}")
        start = len(cases)
        for line, row in enumerate(raw.decode("utf-8").splitlines(), 1):
            if not row or row.startswith("#"):
                continue
            values = row.split("\t")
            require(len(values) == (6 if group == "fresh" else 2), f"Invalid corpus row: {path}:{line}")
            label, request = values[:2]
            historical_check = None
            split = "published"
            if group == "fresh":
                category, historical_outcome, original_split, historical_check = values[2:]
                require(original_split in {"dev", "test"}, f"Invalid split: {path}:{line}")
                split = "held-out" if original_split == "test" else original_split
            elif group == "compose":
                historical_outcome = "refuse" if label in COMPOSE_REFUSALS else "compose"
                category = "compose-boundary" if historical_outcome == "refuse" else "compose-parts"
            else:
                historical_outcome = "compose"
                category = BENCH_CATEGORIES[label]
            case_id = f"{group}:{label}"
            cases.append({
                "id": case_id, "request": request, "language": "en", "category": category,
                "split": split, "game": case_id in BASELINE_GAMES,
                "source": {"revision": revision, "path": path, "line": line, "sha256": digest(raw)},
                "historical_outcome": historical_outcome, "historical_check": historical_check,
            })
        require(len(cases) - start == count, f"Wrong number of requests in {path}.")
    require(sum(case["split"] == "held-out" for case in cases) == 45, "Wrong held-out count.")
    return cases


def validate_execution(execution):
    for name in PRODUCTS:
        product = execution["products"][name]
        require(hex_id(product["revision"], 40), f"Pin the full {name} revision.")
        for field in ("source_sha256", "build_sha256"):
            require(hex_id(product[field], 64), f"Pin the {name} {field}.")
        require(all(nonempty(product[key]) for key in ("route", "source_status", "features")),
                f"Record the {name} route, source status, and features.")
        for key, fields in (("provider", {"name", "model", "config"}), ("device", {"model", "os", "config"})):
            require(text_map(product[key]) and fields <= product[key].keys(), f"Record the {name} {key}.")
    observation = execution["reference_observation"]
    require(hex_id(observation["local_revision"], 40), "Record the local reference revision.")
    if observation["remote_revision"] is not None:
        require(hex_id(observation["remote_revision"], 40), "Record the full remote reference revision.")
    require(all(nonempty(observation[key]) for key in
                ("remote", "ref", "checked_at", "local_source_status", "reference_changes")),
            "Record reference observation details.")
    require(datetime.fromisoformat(observation["checked_at"].replace("Z", "+00:00")).tzinfo is not None,
            "Record the observation time with an offset.")
    performance = execution["performance"]
    require(all(performance[key] in {"comparable", "incomparable"} for key in PERFORMANCE) and
            nonempty(performance["reason"]), "Record performance comparability and its reason.")
    require(execution["held_out_used_for_tuning"] is False, "Held-out requests must not be used for tuning.")


def add_inputs(cases, path):
    if path is None:
        return [], {}
    raw = path.read_bytes()
    inputs = json.loads(raw)
    capabilities = inputs["approved_capabilities"]
    require(isinstance(capabilities, list) and all(nonempty(item) for item in capabilities),
            "Use a list of approved capability IDs.")
    require(len(set(capabilities)) == len(capabilities), "Approved capability IDs must be distinct.")
    rubric = inputs.get("visual_rubric", {})
    require(text_map(rubric), "Use an object of visual dimension descriptions.")
    criteria = inputs["interactions"]
    require(isinstance(criteria, dict) and criteria.keys() <= {case["id"] for case in cases},
            "Use known English case IDs in the interaction map.")
    use_corpus_checks = inputs.get("use_corpus_checks", False)
    require(type(use_corpus_checks) is bool, "use_corpus_checks must be a Boolean.")
    for case in cases:
        if case["id"] in criteria:
            apply_criteria(case, criteria[case["id"]])
        elif use_corpus_checks and case["historical_check"]:
            generated = case["historical_outcome"] == "compose"
            checks = {"requested_workflow": "Operate the requested controls and verify the requested result. "
                      "A refusal passes only for an unavailable capability or an existing permission boundary."}
            if generated:
                checks["corpus_result"] = case["historical_check"]
            rules = []
            if case["game"]:
                checks["game_rules"] = "Check the applicable rules, score or result, completion, and restart from the request."
                rules.append("game_rules")
            apply_criteria(case, {"expected_outcome": "generated" if generated else "either",
                                  "interactions": checks, "game_rules": rules})
    for case in inputs["cases"]:
        require(re.fullmatch(r"[a-z0-9][a-z0-9-]*", case["id"]) is not None, "Invalid supplemental case ID.")
        require(case["language"] in {"en", "ru"}, "Use en or ru for a supplemental request.")
        ids = case["capabilities"]
        require(isinstance(ids, list) and set(ids) <= set(capabilities), "Use approved capability IDs.")
        require(case["language"] == "ru" or ids, "A supplemental case must cover Russian or a capability.")
        frozen = {
            "id": "extra:" + case["id"], "request": case["request"], "language": case["language"],
            "category": case["category"], "split": "supplemental", "capabilities": ids,
            "game": case.get("game", False),
            "source": {"path": path.name, "sha256": digest(raw), "case_id": case["id"]},
        }
        apply_criteria(frozen, case)
        cases.append(frozen)
    require(all(set(case.get("capabilities", [])) <= set(capabilities) for case in cases),
            "Every workflow capability must be in the approved inventory.")
    return capabilities, rubric


def apply_criteria(case, criteria):
    checks = criteria["interactions"]
    require(text_map(checks) and checks, f"Freeze at least one interaction for {case['id']}.")
    outcome = criteria["expected_outcome"]
    require(outcome in {"generated", "refused", "either"}, f"Invalid expected outcome for {case['id']}.")
    rules = criteria.get("game_rules", [])
    require(isinstance(rules, list) and set(rules) <= checks.keys(), f"Invalid game rules for {case['id']}.")
    require(not case["game"] or outcome == "refused" or rules, f"Freeze game-rule checks for {case['id']}.")
    case.update(interactions=checks, expected_outcome=outcome, game_rules=rules)
    if "capabilities" in criteria:
        ids = criteria["capabilities"]
        require(isinstance(ids, list) and len(ids) == len(set(ids)) and all(nonempty(value) for value in ids),
                "Use distinct workflow capability IDs.")
        case["capabilities"] = ids
    if "device_checks" in criteria:
        device_checks = criteria["device_checks"]
        require(isinstance(device_checks, dict) and device_checks.keys() <= checks.keys() and
                all(kind in {"readback", "physical"} for kind in device_checks.values()),
                "Bind required device evidence to frozen interaction IDs.")
        case["device_checks"] = device_checks
    if "change_interaction" in criteria:
        require(criteria["change_interaction"] in checks and outcome != "refused",
                f"Use a frozen change interaction for {case['id']}.")
        case["change_interaction"] = criteria["change_interaction"]


def make_plan(cases, execution, rubric, capabilities, purpose):
    slots = []
    identities = {product: digest(encode(identity)) for product, identity in execution["products"].items()}
    for case in cases:
        request_sha256 = digest(case["request"].encode("utf-8"))
        for product in PRODUCTS:
            for repeat in range(1, 4):
                slots.append({
                    "id": f"{case['id']}:{product}:{repeat}", "case_id": case["id"],
                    "product": product, "repeat": repeat, "request_sha256": request_sha256,
                    "execution_sha256": identities[product],
                })
    return {"format": 1, "purpose": purpose, "corpus_revision": CORPUS_REVISION,
            "execution": execution, "visual_rubric": rubric, "approved_capabilities": capabilities,
            "cases": cases, "slots": slots}


def prepare(args):
    execution = read_json(args.execution)
    validate_execution(execution)
    require(git(args.reference_repo, "rev-parse", "--verify",
                execution["products"]["reference"]["revision"] + "^{commit}").decode().strip() ==
            execution["products"]["reference"]["revision"], "The runnable reference must be a local commit.")
    cases = load_corpus(args.reference_repo, args.corpus_revision)
    capabilities, rubric = add_inputs(cases, args.inputs)
    require(all(nonempty(case["request"]) and nonempty(case["category"]) for case in cases),
            "Every case needs a request and category.")
    requests = [unicodedata.normalize("NFKC", " ".join(case["request"].split())).casefold() for case in cases]
    require(len(set(requests)) == len(cases), "Duplicate normalized request.")
    require(len({case["id"] for case in cases}) == len(cases), "Duplicate case ID.")
    final = make_plan(cases, execution, rubric, capabilities, "final")
    tuning = make_plan([case for case in cases if case["split"] != "held-out"],
                       execution, rubric, capabilities, "tuning")
    args.out.mkdir(mode=0o700)
    data = encode(final)
    (args.out / "final.json").write_bytes(data)
    (args.out / "tuning.json").write_bytes(encode(tuning))
    return {"english_requests": 121, "english_slots": 726, "held_out_requests": 45,
            "slots": len(final["slots"]), "plan_sha256": digest(data)}


def evidence_path(directory, filename, sha256, role):
    require(nonempty(filename) and hex_id(sha256, 64), f"Invalid {role} evidence reference.")
    relative = Path(filename)
    path = (directory / relative).resolve()
    require(not relative.is_absolute() and path.is_relative_to(directory) and path.is_file(),
            f"Missing or out-of-directory {role} evidence.")
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    require(path.stat().st_size > 0 and checksum.hexdigest() == sha256, f"Empty or changed {role} evidence.")
    return path


def validate_evidence(evidence, directory, required):
    require(isinstance(evidence, dict) and required.keys() <= evidence.keys(), "Missing evidence roles.")
    for role, item in evidence.items():
        require(item["origin"] in required.get(role, {"native-device", "real-run", "human-review"}),
                f"Wrong {role} evidence origin.")
        evidence_path(directory, item["path"], item["sha256"], role)


def match_packet(packet, slot, plan_hash):
    require(packet["slot_id"] == slot["id"] and packet["plan_sha256"] == plan_hash and
            all(packet[key] == slot[key] for key in ("request_sha256", "execution_sha256")),
            "Capture does not match the frozen slot identity.")


def android_packet(directory, evidence_dir, slots, products, plan_hash):
    path = directory / "capture.json"
    require(not path.is_symlink(), "Android capture manifest must be inside its capture directory.")
    data = path.read_bytes()
    manifest = json.loads(data)
    require(isinstance(manifest["command"], list) and manifest["command"] and
            all(nonempty(item) for item in manifest["command"]), "Android capture command is missing.")
    slot = slots[manifest["slot"]["id"]]
    product = products["candidate"]
    require(slot["product"] == "candidate" and manifest["slot"] == slot and
            manifest["plan_sha256"] == plan_hash and product["route"] == ANDROID_ROUTE and
            manifest["execution"] == product and digest(encode(manifest["apks"])) == product["build_sha256"],
            "Android capture does not match the frozen execution identity.")
    device = manifest["device"]
    require(isinstance(device, dict) and all(nonempty(device.get(key)) and
            device[key] == product["device"][key] for key in ANDROID_DEVICE_KEYS),
        "Android capture device does not match the frozen identity.")
    run_id = manifest["run_id"]
    require(hex_id(run_id, 32), "Invalid Android capture run ID.")
    report = {}
    artifacts = manifest.get("artifacts", {})
    if "report.json" in artifacts:
        report = read_json(evidence_path(directory, "report.json", artifacts["report.json"], "Android report"))
        match_packet(report, slot, plan_hash)
        require(report["run_id"] == run_id and all(report[key] == product[key] for key in IDENTITY_KEYS),
                "Android report does not match its capture and source identity.")
        require(all(report["device"][key] == device[key] for key in ("model", "os", "fingerprint")),
                "Android report device does not match the frozen device.")
    if manifest["status"] == "device-complete":
        require(report.get("phase") == "complete", "Android completion has no matching terminal report.")
    measurements = report.get("measurements", {})
    unavailable = ["operation_measurement_trace", "route_stage_timings", "parts_only", "frame_p95_us",
                   "change_ms", "interactions", "blind_visual_review", "recovery"]
    unavailable.extend(key for key in ("first_ui_ms", "completed_ui_ms", "provider_http_calls",
                                       "correction_lineage", "runtime_usability") if measurements.get(key) is None)
    if not (report.get("chat_screen") or report.get("review_screen")):
        unavailable.append("screen")
    if "motion.mp4" not in artifacts:
        unavailable.append("motion")
    observations = {
        "kind": "android", "run_id": run_id, "capture_status": manifest["status"],
        "observed_outcome": report.get("chat_outcome"),
        "failure": report.get("failure_code") or report.get("failure_stage"),
        "generation": {"path": str(path.relative_to(evidence_dir)), "sha256": digest(data), "origin": "real-run"},
        "unavailable_in_capture": unavailable,
    }
    return slot["id"], observations, (report, artifacts)


def host_packet(directory, slots, products, plan_hash):
    product = products["reference"]
    attempts = []
    lineage = None
    previous_hash = None
    require(all(path.name in {"attempt-1", "attempt-2"}
                for path in directory.glob("attempt-*")), "Unexpected reference host attempt.")
    for number in (1, 2):
        attempt_dir = directory / f"attempt-{number}"
        if not attempt_dir.exists():
            continue
        result = attempt_dir / "result.json"
        path = result if result.exists() else attempt_dir / "started.json"
        require(path.resolve().is_relative_to(directory), "Reference attempt must be inside its capture directory.")
        raw = path.read_bytes()
        packet = json.loads(raw)
        slot = slots[packet["slot_id"]]
        match_packet(packet, slot, plan_hash)
        require(slot["product"] == "reference" and product["route"] == HOST_ROUTE and packet["format"] == 1 and
                all(packet["source"][key] == product[key] for key in (*IDENTITY_KEYS, "route", "provider")) and
                digest(encode(packet["runtime"])) == product["build_sha256"],
                "Reference host capture does not match the frozen execution identity.")
        mode = packet["mode"]
        origin = {"live": "reference-host", "synthetic": "synthetic"}.get(mode)
        require(origin is not None and packet["origin"] == origin and packet["attempt"] == number and
                hex_id(packet["generation_id"], 32) and hex_id(packet["attempt_id"], 32) and
                packet["status"] == ("complete" if path == result else "started"),
                "Invalid reference host attempt.")
        outcome = packet.get("host_outcome")
        require(outcome in ({"built", "not-built", "failed"} if path == result else {None}),
                "Invalid reference host outcome.")
        require(outcome != "failed" or nonempty(packet["failure"]), "Reference failure has no reason.")
        identity = (slot["id"], packet["generation_id"], mode)
        if number == 2:
            require(identity == lineage and packet["previous_attempt_sha256"] == previous_hash and
                    attempts[0]["host_outcome"] == "failed" and packet["attempt_id"] != attempts[0]["attempt_id"],
                    "Invalid reference host correction lineage.")
        lineage = identity
        previous_hash = digest(raw)
        attempts.append({"attempt": number, "attempt_id": packet["attempt_id"], "status": packet["status"],
                         "host_outcome": outcome, "failure": packet.get("failure"), "sha256": previous_hash})
    require(attempts, "No reference host attempts found.")
    return lineage[0], {"kind": "reference-host", "run_id": lineage[1], "mode": lineage[2],
                        "attempts": attempts, "unavailable_in_capture": sorted(
                            key for key, value in packet["comparison_evidence"].items() if value == "unavailable")}, None


def read_captures(parent, evidence_dir, slots, products, plan_hash):
    captures, invalid = {}, []
    seen_slots, seen_runs = set(), set()
    if parent is None:
        return captures, invalid
    parent = parent.resolve()
    require(parent.is_dir() and parent.is_relative_to(evidence_dir),
            "The capture parent must be inside the evidence directory.")
    for directory in sorted(parent.iterdir()):
        if not directory.is_dir():
            continue
        try:
            require(not directory.is_symlink(), "Capture directory must not be a symbolic link.")
            if (directory / "capture.json").exists():
                slot_id, summary, device_report = android_packet(directory, evidence_dir, slots, products, plan_hash)
            else:
                slot_id, summary, device_report = host_packet(directory, slots, products, plan_hash)
            if slot_id in seen_slots:
                captures.pop(slot_id, None)
                raise ValueError("The slot already has a capture.")
            seen_slots.add(slot_id)
            run = (summary["kind"], summary["run_id"])
            require(run not in seen_runs, "Capture run identity was reused.")
            seen_runs.add(run)
            summary["directory"] = str(directory.relative_to(evidence_dir))
            captures[slot_id] = (summary, device_report)
        except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
            invalid.append({"directory": directory.name, "reason": str(error)})
    return captures, invalid


def merge_android(record, capture, directory):
    summary, (report, artifacts) = capture
    require(summary["capture_status"] == "device-complete", "Android capture has no terminal device result.")
    require(record["capture_sha256"] == summary["generation"]["sha256"],
            "Android supplement does not match its capture hash.")
    evidence = record.get("evidence", {})
    require("telemetry" in evidence, "Android supplement needs an operation measurement trace.")
    require(not {"fresh_state", "outcome", "attempts", "generation_id", "artifact_id", "parts_only",
                 "failure"} & record.keys() and
            not {"generation", "screen", "motion"} & evidence.keys() and
            not {"first_ui_ms", "completed_ui_ms", "change_ms"} & record.get("timings", {}).keys(),
            "Android supplement cannot replace capture or trace fields.")
    item = evidence["telemetry"]
    require(item["origin"] == "real-run", "Android measurement trace needs real-run evidence.")
    trace = read_json(evidence_path(directory, item["path"], item["sha256"], "Android measurement trace"))
    generation_id = report.get("operation_id") or report["run_id"]
    require(trace["format"] == 1 and trace["generation_id"] == generation_id and
            trace["provider_count_source"] == "http-boundary" and
            all(trace[key] == record[key] for key in ("slot_id", "plan_sha256", "request_sha256",
                                                      "execution_sha256", "capture_sha256")),
            "Android measurement trace does not match the frozen slot and operation.")
    outcome = {"artifact-committed": "generated", "request-failed": "failed",
               "complete-without-artifact": "refused"}.get(report.get("chat_outcome"))
    require(outcome is not None, "Android chat has no comparison outcome.")
    require(outcome != "generated" or nonempty(report.get("operation_id")),
            "Android artifact has no generation operation identity.")
    if outcome == "refused":
        require(record.get("refusal_confirmed") is True, "Android chat without an artifact needs a reviewed refusal.")
    merged = {
        **record, "outcome": outcome, "generation_id": generation_id, "attempts": trace["attempts"],
        "fresh_state": {"draft": report["fresh_draft"], "session": report["fresh_conversation"],
                        "store": report["fresh_store"]},
        "evidence": {**evidence, "generation": summary["generation"]},
    }
    if outcome == "failed":
        merged["failure"] = report.get("failure_code") or "request-failed"
    if outcome != "failed":
        screen = "review.png" if outcome == "generated" else "chat.png"
        observed = (report.get("review_screen") is True and report.get("open_card_action") is True
                    if outcome == "generated" else report.get("chat_screen") is True)
        for role, filename, captured in (("screen", screen, observed is True),
                                         ("motion", "motion.mp4", True)):
            if captured and filename in artifacts:
                merged["evidence"][role] = {"path": str(Path(summary["directory"]) / filename),
                                            "sha256": artifacts[filename], "origin": "native-device"}
    if outcome == "generated":
        events = trace.get("events", {})
        sent, first, completed = (events.get(key) for key in ("send_accepted", "first_presented_ui", "validated_usable_ui"))
        require(trace.get("clock") == "monotonic-ms" and all(integer(value) for value in (sent, first, completed)) and
                sent < first <= completed, "Android generated UI needs measured monotonic frame times.")
        merged.update(artifact_id=report["artifact_id"], parts_only=trace["parts_only"],
                      timings={**record.get("timings", {}), "first_ui_ms": first - sent,
                               "completed_ui_ms": completed - sent})
        if "change_events" in trace:
            start, end = (trace["change_events"][key] for key in ("accepted", "usable"))
            require(integer(start) and integer(end) and start < end, "Android change needs measured monotonic times.")
            merged["timings"]["change_ms"] = end - start
    return merged


def validate_record(record, slot, case, plan, plan_hash, directory):
    require(record["plan_sha256"] == plan_hash, "Wrong frozen plan identity.")
    require(record["execution_sha256"] == slot["execution_sha256"], "Wrong execution identity.")
    require(record["request_sha256"] == slot["request_sha256"], "Wrong exact request identity.")
    require(record["mode"] in {"real", "synthetic"}, "Unknown generation mode.")
    generation = record["generation_id"]
    require(nonempty(generation), "Missing generation identity.")
    require(all(nonempty(record["fresh_state"][key]) for key in STATE_KEYS), "Missing fresh state identity.")
    attempts = record["attempts"]
    require(isinstance(attempts, list) and 1 <= len(attempts) <= 2, "Use one attempt and at most one correction.")
    for number, attempt in enumerate(attempts, 1):
        require(attempt["number"] == number and attempt["generation_id"] == generation,
                "A correction must belong to the same generation.")
        require(attempt["outcome"] in OUTCOMES and integer(attempt["provider_calls"]),
                "Invalid attempt outcome or provider call count.")
        stages = attempt["stages"]
        require(isinstance(stages, dict) and stages and all(nonempty(key) and integer(value)
                for key, value in stages.items()), "Record measured route stage durations.")
    if len(attempts) == 2:
        require(attempts[0]["outcome"] == "failed", "Only a failed attempt can start a correction.")
    outcome = record["outcome"]
    require(outcome == attempts[-1]["outcome"], "The final attempt must match the outcome.")
    if record["mode"] == "synthetic":
        require(all(attempt["provider_calls"] == 0 and attempt["outcome"] == "failed" for attempt in attempts),
                "Synthetic records cannot claim provider calls or generated results.")
        validate_evidence(record["evidence"], directory, {"generation": {"synthetic"}})
        require(all(item["origin"] == "synthetic" for item in record["evidence"].values()),
                "Synthetic evidence cannot use a native or real label.")
        return
    roles = {"generation": {"real-run"}}
    require(isinstance(record["critical_defects"], list) and
            all(nonempty(item) for item in record["critical_defects"]), "Record critical defects explicitly.")
    if outcome == "failed":
        require(nonempty(record["failure"]), "Record the reason for a failed generation.")
    else:
        require(sum(attempt["provider_calls"] for attempt in attempts) > 0, "Record at least one real provider call.")
        roles.update({"screen": {"native-device"}, "motion": {"native-device"},
                      "interactions": {"human-review"}, "visual": {"human-review"}})
        if outcome == "generated":
            timings = record["timings"]
            require(nonempty(record["artifact_id"]) and type(record["parts_only"]) is bool,
                    "Record the generated artifact and parts-only status.")
            require(integer(timings["first_ui_ms"]) and integer(timings["completed_ui_ms"]) and
                    timings["first_ui_ms"] <= timings["completed_ui_ms"], "Invalid send-to-UI timings.")
            if "change_ms" in timings:
                require(case.get("change_interaction") and integer(timings["change_ms"]),
                        "Change timing needs a frozen change interaction.")
            if case.get("change_interaction"):
                require("change_ms" in timings, "Missing timing for the frozen change interaction.")
            if plan["execution"]["performance"]["frames"] == "comparable":
                require(integer(timings["frame_p95_us"], 1), "Missing frame p95.")
            if "frame_p95_us" in timings:
                require(integer(timings["frame_p95_us"], 1), "Invalid frame p95.")
                roles["frame_trace"] = {"native-device"}
        checks = record["interactions"]
        require(isinstance(checks, dict) and checks.keys() == case.get("interactions", {}).keys() and
                all(value in {"pass", "fail", "unavailable"} for value in checks.values()),
                "Record each frozen interaction exactly once.")
        visual = record["visual"]
        require(visual["blind"] is True and nonempty(visual["review_id"]) and
                visual["ratings"].keys() == plan["visual_rubric"].keys() and
                all(integer(value, 1) and value <= 5 for value in visual["ratings"].values()),
                "Record blind visual ratings for every frozen dimension.")
    if outcome == "failed" or len(attempts) == 2 or outcome == "refused":
        require(record["recovery"] in {"pass", "fail", "unavailable"}, "Record failure recovery.")
        if record["recovery"] != "unavailable":
            roles["recovery"] = {"human-review"}
    validate_evidence(record["evidence"], directory, roles)


def preflight(plan):
    execution = plan["execution"]
    issues = []
    if execution["reference_observation"]["remote_revision"] != execution["products"]["reference"]["revision"]:
        issues.append("The runnable reference does not match an observed remote revision.")
    if execution["products"]["reference"]["route"] == HOST_ROUTE:
        issues.append("Host-only reference execution cannot pass native comparison.")
    if not plan["visual_rubric"]:
        issues.append("No frozen blind visual rubric.")
    extras = [case for case in plan["cases"] if case["split"] == "supplemental"]
    if not any(case["language"] == "ru" for case in extras):
        issues.append("No frozen Russian workflow.")
    capabilities = set(plan["approved_capabilities"])
    covered = {cap for case in extras for cap in case["capabilities"]}
    if not capabilities or capabilities - covered:
        issues.append("The approved capability inventory is absent or lacks frozen workflows.")
    missing = [case["id"] for case in plan["cases"] if not case.get("interactions")]
    if missing:
        issues.append(f"Frozen shared interaction criteria are absent for {len(missing)} requests.")
    if not any(case.get("change_interaction") for case in plan["cases"]):
        issues.append("No frozen change interaction for change timing.")
    return issues


def summarize(rows, performance, rubric):
    summary = {}
    failures = []
    for product in PRODUCTS:
        expected = [record for slot, case, record in rows if slot["product"] == product]
        real = [record for record in expected if record and record["mode"] == "real"]
        generated = [record for record in real if record["outcome"] == "generated"]
        stages = defaultdict(list)
        for record in real:
            for attempt in record["attempts"]:
                for stage, elapsed_ms in attempt["stages"].items():
                    stages[stage].append(elapsed_ms)
        values = {}
        for key in ("first_ui_ms", "completed_ui_ms", "frame_p95_us", "change_ms"):
            samples = [record["timings"][key] for record in generated if key in record["timings"]]
            values[key] = {"median": median(samples) if samples else None, "count": len(samples)}
        summary[product] = {
            "expected": len(expected), "real": len(real), "generated": len(generated),
            "failed": sum(record["outcome"] == "failed" for record in real),
            "failure_rate": (sum(record["outcome"] == "failed" for record in real) / len(real)) if real else None,
            "refused": sum(record["outcome"] == "refused" for record in real),
            "parts_only_rate": mean(record["parts_only"] for record in generated) if generated else None,
            "functional_pass": sum(record.get("functional_pass") is True for record in real),
            "recovery": dict(Counter(record["recovery"] for record in real if "recovery" in record)),
            "route_stage_ms": {key: {"median": median(samples), "count": len(samples)}
                               for key, samples in stages.items()},
            "performance": values,
            "visual": {key: mean(samples) if (samples := [record["visual"]["ratings"][key]
                       for record in real if "visual" in record]) else None for key in rubric},
        }
    pairs = defaultdict(dict)
    for slot, _, record in rows:
        if record and record["mode"] == "real" and record["outcome"] == "generated":
            pairs[(slot["case_id"], slot["repeat"])][slot["product"]] = record["timings"]
    matched = {}
    for key in ("first_ui_ms", "completed_ui_ms", "frame_p95_us", "change_ms"):
        samples = [(pair["candidate"][key], pair["reference"][key]) for pair in pairs.values()
                   if all(key in pair.get(product, {}) for product in PRODUCTS)]
        matched[key] = {"count": len(samples)}
        if samples:
            matched[key].update(zip(PRODUCTS, map(median, zip(*samples))))
    left, right = (summary[product] for product in PRODUCTS)
    if all(summary[product]["real"] == summary[product]["expected"] for product in PRODUCTS):
        for key in ("functional_pass", "generated"):
            if left[key] < right[key]:
                failures.append(f"{key} regression")
        for key in rubric:
            a, b = left["visual"][key], right["visual"][key]
            if a is not None and b is not None and a < b:
                failures.append(f"visual regression: {key}")
        for metric, keys in (("latency", ("first_ui_ms", "completed_ui_ms")), ("frames", ("frame_p95_us",))):
            if performance[metric] == "comparable":
                for key in keys:
                    pair = matched[key]
                    if pair["count"] and pair["candidate"] > pair["reference"]:
                        failures.append(f"performance regression: {key}")
    return {"products": summary, "matched_performance": matched, "regressions": failures}


def load_final_plan(args):
    data = args.plan.read_bytes()
    require(digest(data) == args.plan_sha256, "The plan does not match the frozen SHA-256.")
    plan = json.loads(data)
    require(plan["format"] == 1 and plan["purpose"] == "final", "Audit needs a final comparison plan.")
    validate_execution(plan["execution"])
    require(plan["corpus_revision"] == CORPUS_REVISION and
            sum(case["split"] != "supplemental" for case in plan["cases"]) == 121 and
            sum(case["split"] == "held-out" for case in plan["cases"]) == 45,
            "The plan lacks the complete approved English corpus.")
    expected = make_plan(plan["cases"], plan["execution"], plan["visual_rubric"],
                         plan["approved_capabilities"], "final")
    require(plan["slots"] == expected["slots"], "The plan lacks the complete independent matrix.")
    return plan


def audit_inputs(args, plan, products):
    slots = {slot["id"]: slot for slot in plan["slots"]}
    cases = {case["id"]: case for case in plan["cases"]}
    require(len(cases) == len(plan["cases"]), "Duplicate case ID.")
    directory = args.evidence_dir.resolve()
    require(directory.is_dir(), "The evidence directory does not exist.")
    captures, invalid_captures = read_captures(args.capture_dir, directory, slots, products, args.plan_sha256)
    return slots, cases, directory, captures, invalid_captures


def audit(args):
    plan = load_final_plan(args)
    products = plan["execution"]["products"]
    slots, cases, directory, captures, invalid_captures = audit_inputs(args, plan, products)
    accepted = {}
    seen_slots = set()
    identities = set()
    invalid = []
    for line, text in enumerate(args.results.read_text(encoding="utf-8").splitlines(), 1):
        if not text.strip():
            continue
        try:
            record = json.loads(text)
            slot_id = record["slot_id"]
            require(slot_id in slots, "Unknown slot.")
            require(slot_id not in seen_slots, "The slot already has a record.")
            seen_slots.add(slot_id)
            slot = slots[slot_id]
            case = cases[slot["case_id"]]
            if record["mode"] == "real":
                route = products[slot["product"]]["route"]
                require(route != HOST_ROUTE, "A host-only capture cannot supply a native comparison record.")
                if route == ANDROID_ROUTE:
                    require(slot_id in captures, "A matching Android capture is required.")
                    match_packet(record, slot, args.plan_sha256)
                    record = merge_android(record, captures[slot_id], directory)
            validate_record(record, slot, case, plan, args.plan_sha256, directory)
            keys = {("generation", record["generation_id"])}
            keys.update((slot["product"], key, record["fresh_state"][key]) for key in STATE_KEYS)
            if record["mode"] == "real" and record["outcome"] == "generated":
                keys.add((slot["product"], "artifact", record["artifact_id"]))
            require(not keys & identities, "Generation, artifact, or fresh state identity was reused.")
            identities.update(keys)
            if record["mode"] == "real" and record["outcome"] != "failed":
                record["functional_pass"] = (
                    case.get("expected_outcome") in {record["outcome"], "either"} and
                    bool(record["interactions"]) and all(result == "pass" for result in record["interactions"].values()))
            accepted[slot_id] = record
        except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
            invalid.append({"line": line, "reason": str(error)})
    groups = defaultdict(list)
    splits = defaultdict(Counter)
    slots_with_issues = []
    for slot_id, slot in slots.items():
        case = cases[slot["case_id"]]
        record = accepted.get(slot_id)
        split = case["split"]
        groups[f"{split}/{case['category']}"].append((slot, case, record))
        if record is None:
            status = "incomplete" if slot_id in captures else "missing"
        else:
            status = "synthetic" if record["mode"] == "synthetic" else record["outcome"]
        splits[split][status] += 1
        if status in {"generated", "refused", "failed"}:
            if (status == "failed" or not record.get("functional_pass") or record["critical_defects"] or
                    record.get("recovery", "pass") != "pass"):
                slots_with_issues.append(slot_id)
    performance = plan["execution"]["performance"]
    categories = {key: summarize(rows, performance, plan["visual_rubric"]) for key, rows in sorted(groups.items())}
    counts = sum(splits.values(), Counter())
    blockers = preflight(plan)
    if counts["missing"] or counts["incomplete"] or counts["synthetic"] or invalid or invalid_captures:
        blockers.append("The matrix has missing, incomplete, synthetic, or invalid records.")
    if slots_with_issues:
        blockers.append("The matrix has failed generations, failed checks, or critical defects.")
    if any(group["regressions"] for group in categories.values()):
        blockers.append("At least one category regressed.")
    for key, metric in (("latency", "completed_ui_ms"), ("frames", "frame_p95_us")):
        if performance[key] != "comparable":
            blockers.append(f"{key.capitalize()} conditions are incomparable.")
        elif not any(group["matched_performance"][metric]["count"] for group in categories.values()):
            blockers.append(f"No matched generated UI evidence for {key}.")
    if not any(group["matched_performance"]["change_ms"]["count"] for group in categories.values()):
        blockers.append("No matched change timing evidence.")
    return {
        "gate": "blocked" if blockers else "pass", "blockers": blockers, "execution": plan["execution"],
        "plan_sha256": args.plan_sha256, "slots": len(slots), "missing_slots": counts["missing"],
        "incomplete_slots": counts["incomplete"], "invalid_captures": invalid_captures,
        "captures": {slot_id: {**summary, "audit_status":
                     "complete" if accepted.get(slot_id, {}).get("mode") == "real" else "incomplete"}
                     for slot_id, (summary, _) in captures.items()},
        "synthetic_slots": counts["synthetic"], "failed_slots": counts["failed"], "splits": splits,
        "invalid_records": invalid, "slots_with_issues": slots_with_issues, "categories": categories,
        "provider_calls": sum(attempt["provider_calls"] for record in accepted.values()
                              for attempt in record["attempts"]),
    }


def functional_capture(capture, record, directory):
    summary, device = capture
    outcome = record["outcome"]
    require(outcome in OUTCOMES, "Invalid reviewed outcome.")
    if summary["kind"] == "android":
        require(record["capture_sha256"] == summary["generation"]["sha256"] and
                summary["capture_status"] == "device-complete", "Review needs a matching terminal Android capture.")
        report = device[0]
        dispatches = report.get("operation_trace", {}).get("http_dispatches")
        calls = report.get("measurements", {}).get("provider_http_calls")
        require(isinstance(dispatches, list) and integer(calls, 1) and len(dispatches) == calls,
                "Android capture needs actual HTTP dispatch evidence.")
        observed = {"artifact-committed": "generated", "request-failed": "failed",
                    "complete-without-artifact": "refused"}.get(report["chat_outcome"])
        artifact = report.get("artifact_id")
        state = tuple(report.get("fresh_" + field) for field in ("draft", "conversation", "store"))
        corrected = any(event.get("attempt", 1) > 1 or event.get("disposition") == "correct"
                        for event in report["operation_trace"]["generation"])
        require(outcome == "failed" or all(nonempty(value) for value in state),
                "Android result needs fresh draft, conversation and store identities.")
    else:
        attempt = summary["attempts"][-1]
        require(summary["mode"] == "live" and attempt["status"] == "complete" and
                record["capture_sha256"] == attempt["sha256"], "Review needs a matching live reference host result.")
        observed = {"built": "generated", "failed": "failed", "not-built": "refused"}[attempt["host_outcome"]]
        attempt_path = f"{summary['directory']}/attempt-{attempt['attempt']}"
        packet = read_json(evidence_path(directory, f"{attempt_path}/result.json", attempt["sha256"], "host result"))
        artifact = None
        if observed == "generated":
            artifact = packet["artifacts"]["program.vera"]["sha256"]
            evidence_path(directory, f"{attempt_path}/program.vera", artifact, "host program")
        state = ()
        corrected = len(summary["attempts"]) == 2
    require(observed == outcome, "Review outcome differs from its capture.")
    require(outcome != "generated" or nonempty(artifact), "Generated result has no captured artifact identity.")
    require(outcome != "refused" or record.get("refusal_confirmed") is True,
            "An absent artifact needs reviewed refusal evidence.")
    return artifact, state, corrected


def audit_functional(args):
    plan = load_final_plan(args)
    products = plan["execution"]["products"]
    require(products["candidate"]["route"] == ANDROID_ROUTE and products["reference"]["route"] == HOST_ROUTE,
            "Functional audit requires Android chat and the reference host runtime.")
    slots, cases, directory, captures, invalid_captures = audit_inputs(args, plan, products)
    seen, state_ids, invalid, complete = set(), set(), [], {}
    groups = defaultdict(Counter)
    coverage, passed_slots = set(), set()
    critical = []
    for line, text in enumerate(args.results.read_text(encoding="utf-8").splitlines(), 1):
        if not text.strip():
            continue
        try:
            record = json.loads(text)
            slot = slots[record["slot_id"]]
            slot_id = slot["id"]
            require(slot_id not in seen, "The slot already has a review.")
            seen.add(slot_id)
            match_packet(record, slot, args.plan_sha256)
            require(slot_id in captures, "Review needs a matching capture.")
            artifact, state, corrected = functional_capture(captures[slot_id], record, directory)
            keys = {(index, value) for index, value in enumerate(state) if value is not None}
            require(not keys & state_ids, "An Android fresh-state identity was reused.")
            case = cases[slot["case_id"]]
            evidence = record["review"]
            proof = read_json(evidence_path(directory, evidence["path"], evidence["sha256"], "functional review"))
            match_packet(proof, slot, args.plan_sha256)
            require(proof["capture_sha256"] == record["capture_sha256"] and proof["artifact"] == artifact,
                    "Functional review does not match the terminal capture and artifact.")
            defects = proof["critical_defects"]
            require(isinstance(defects, list) and all(nonempty(item) for item in defects), "Record critical defects.")
            outcome = record["outcome"]
            checks = proof["checks"]
            if outcome == "failed":
                require(not checks and nonempty(proof["failure"]), "Record the failure without claiming interaction results.")
            else:
                require(isinstance(checks, dict) and checks and checks.keys() == case.get("interactions", {}).keys()
                        and all(row["status"] in {"pass", "fail", "unavailable"} and nonempty(row["observed"])
                                for row in checks.values()), "Review every frozen check and record its observed result.")
            recovery_required = outcome != "generated" or corrected
            recovery = proof.get("recovery")
            require(not recovery_required or recovery in {"pass", "fail", "unavailable"}, "Review recovery.")
            passed = (outcome != "failed" and case.get("expected_outcome") in {outcome, "either"}
                      and all(row["status"] == "pass" for row in checks.values())
                      and (not recovery_required or recovery == "pass") and not defects)
            state_ids.update(keys)
            complete[slot_id] = outcome
            if defects:
                critical.append(slot_id)
            if passed:
                groups[f"{case['split']}/{case['category']}"][slot["product"]] += 1
                coverage.add((case["id"], slot["product"]))
                passed_slots.add(slot_id)
        except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
            invalid.append({"line": line, "reason": str(error)})

    extras = [case for case in plan["cases"] if case["split"] == "supplemental"]
    approved = set(plan["approved_capabilities"])
    covered = {capability for case in plan["cases"] for capability in case.get("capabilities", [])}
    gaps = [case["id"] for case in plan["cases"] if not case.get("interactions")]
    regressions = sorted(key for key, counts in groups.items() if counts["candidate"] < counts["reference"])
    uncovered = sorted(case["id"] for case in plan["cases"] if
                       any((case["id"], product) not in coverage for product in PRODUCTS))
    blockers = []
    if plan["execution"]["reference_observation"]["remote_revision"] != products["reference"]["revision"]:
        blockers.append("The runnable reference does not match the observed remote revision.")
    if gaps or not approved or approved - covered or not any(case["language"] == "ru" for case in extras):
        blockers.append("The plan lacks frozen checks, Russian workflows, or approved capability coverage.")
    if len(complete) != len(slots) or invalid or invalid_captures:
        blockers.append("The live functional matrix has missing, incomplete, or invalid evidence.")
    if uncovered or regressions:
        blockers.append("At least one request lacks passing evidence on both products or a category regressed.")
    if critical:
        blockers.append("The matrix contains critical defects.")
    case_results = {case_id: {product: [] for product in PRODUCTS} for case_id in cases}
    for slot_id, slot in slots.items():
        status = "pass" if slot_id in passed_slots else "fail" if slot_id in complete else "missing"
        case_results[slot["case_id"]][slot["product"]].append(status)
    return {"gate": "blocked" if blockers else "pass", "scope": "android-and-reference-host-functionality",
            "plan_sha256": args.plan_sha256, "blockers": blockers, "planned_slots": len(slots),
            "complete_slots": len(complete), "outcomes": Counter(complete.values()), "critical_slots": critical,
            "uncovered_cases": uncovered, "categories": groups, "cases": case_results, "regressions": regressions,
            "invalid_records": invalid, "invalid_captures": invalid_captures}


def coverage(args):
    plan = load_final_plan(args)
    families = {
        "calculated-values": {"unit-conversion", "money", "home-diy", "hard-math"},
        "timers-and-records": {"cooking", "fitness", "goals", "health", "logs", "calendar", "dose-schedule", "due-items",
                               "idea-deck", "interval-timer", "itinerary", "itinerary-source-selection", "points-ledger",
                               "quick-log", "reading-status", "timer"},
        "general-parts": {"compose-parts", "terse"},
        "custom-and-boundary": {"compose-boundary", "beyond-logic", "embedded-scene", "grid-game", "near-miss"},
        "device-and-live-data": {"audio-torch", "navigation-calendar", "outside-data", "phone-action"},
    }
    rows = []
    for case in plan["cases"]:
        family = next((name for name, categories in families.items() if case["category"] in categories), None)
        rows.append({"case_id": case["id"], "split": case["split"], "family": family,
                     "expected_outcome": case["expected_outcome"], "checks": sorted(case["interactions"]),
                     "device_checks": case.get("device_checks", {}), "capabilities": case.get("capabilities", [])})
    return {"plan_sha256": args.plan_sha256, "planning_status": "mapped" if all(row["family"] for row in rows) else "incomplete",
            "functional_status": "unverified", "cases": rows, "case_count": len(rows),
            "check_count": sum(len(row["checks"]) for row in rows), "slot_count": len(plan["slots"]),
            "families": Counter(row["family"] for row in rows), "splits": Counter(row["split"] for row in rows)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare")
    preparation.add_argument("--reference-repo", type=Path, required=True)
    preparation.add_argument("--corpus-revision", required=True)
    preparation.add_argument("--execution", type=Path, required=True)
    preparation.add_argument("--inputs", type=Path)
    preparation.add_argument("--out", type=Path, required=True)
    inventory = commands.add_parser("coverage")
    inventory.add_argument("--plan", type=Path, required=True)
    inventory.add_argument("--plan-sha256", required=True)
    for name in ("audit", "audit-functional"):
        auditing = commands.add_parser(name)
        auditing.add_argument("--plan", type=Path, required=True)
        auditing.add_argument("--plan-sha256", required=True)
        auditing.add_argument("--results", type=Path, required=True)
        auditing.add_argument("--evidence-dir", type=Path, required=True)
        auditing.add_argument("--capture-dir", type=Path, required=name == "audit-functional")
    args = parser.parse_args()
    try:
        result = {"prepare": prepare, "audit": audit, "audit-functional": audit_functional, "coverage": coverage}[args.command](args)
    except (ValueError, KeyError, TypeError, AttributeError, OSError) as error:
        parser.exit(2, f"{error}\n")
    print(encode(result).decode(), end="")
    return int(result.get("gate") == "blocked" or result.get("planning_status") == "incomplete")


if __name__ == "__main__":
    raise SystemExit(main())
