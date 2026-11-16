#!/usr/bin/env python3
"""Operate saved corpus apps with scenarios and judge the final screens on the host.

A scenario file maps a case ID to scenarios: {"case": [{"name": "...", "do": [...], "expect": [...], "alt": "group"}]}.
Scenarios with the same optional "alt" group are alternatives, for example a row that can be first or last.
Operations bind to real controls by a label pattern, so one scenario works for every generated version:
  ["set", "label regex", value]        input, numberInput, slider, toggle, checkbox, time or date field
  ["choose", "label regex", "option regex"]   segmented, select, chips, or tabs
  ["tap", "label regex"]               button, icon, chip, or list item
  ["setRow", "label regex", row, value] and ["tapRow", "label regex", row]   controls in list rows
  ["wait", milliseconds], ["pointer", "surface regex", x, y], or {"raw": app step}
Expectations, judged on the visible text of the final screen:
  "num:68.04"   a visible number within rounding tolerance
  "text:10:00"  visible text contains the value (case-insensitive)
  "absent:milk" no visible text contains the value
  {"raw": expect step} runs inside the app checker, for example an effect expectation.

The device run uses AppScenarioCheckTest. Build and install the debug and Android test APKs first.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/compose_acceptance"))
from run import ADB, RUNNER, adb_command, child_environment  # noqa: E402

TEST = "com.alpha.assistant.tools.AppScenarioCheckTest#runScenarios"
FILES = "/sdcard/Android/data/com.alpha.assistant/files"
VALUE_TYPES = {"input", "numberInput", "slider", "toggle", "checkbox", "switch", "timeInput", "dateInput", "stepper"}
CHOICE_TYPES = {"segmented", "select", "chips", "tabs", "radio", "chipGroup"}
TAP_TYPES = {"button", "icon", "actionChip", "listItem", "chip", "fab", "card", "iconButton"}


class Unbound(Exception):
    pass


MODALS = {"dialog", "bottomSheet"}


def controls(ui, state=None):
    """Every node with an action, whether it sits in a list row template, and whether it is hidden at start.
    A node is hidden at start inside a dialog or under a visibleBind that is false in the initial state."""
    found = []
    state = state or {}

    def visit(node, row, modal):
        if isinstance(node, dict):
            if node.get("action") or node.get("type") == "canvas":
                found.append({"node": node, "row": row, "modal": modal})
            inside = modal or node.get("type") in MODALS or ("visibleBind" in node and not state.get(node["visibleBind"]))
            for key, value in node.items():
                visit(value, row or key == "rowTemplate", inside)
        elif isinstance(node, list):
            for item in node:
                visit(item, row, modal)

    visit(ui, False, False)
    return found


def label(node):
    return " ".join(str(node.get(key, "")) for key in ("text", "placeholder", "action", "bind", "icon", "id"))


def bind(spec, pattern, kinds, row):
    """The alternatives of the pattern are tried in order. Visible label text wins over IDs."""
    found = [item for item in controls(spec["ui"], spec.get("initialState")) if item["node"].get("type") in kinds and item["row"] == row]
    # A control on the first screen wins over one with the same label that is hidden at start, such as an edit form.
    groups = [[item["node"] for item in found if not item["modal"]], [item["node"] for item in found if item["modal"]]]
    nodes = groups[0] + groups[1]
    seen = []
    for alternative in pattern.split("|"):
        regex = re.compile(alternative, re.I)
        for text in (lambda node: str(node.get("text", "")), label):
            for group in groups:
                chosen = [node for node in group if regex.search(text(node))]
                if len({node.get("action") for node in chosen}) == 1:
                    return chosen[0]
                seen += chosen
                if chosen:
                    break
    found = sorted({f'{node.get("type")} "{node.get("text", "")}" [{node.get("action")}]' for node in seen or nodes})
    raise Unbound(f"No single control matches /{pattern}/ in {sorted(kinds)}: {found}")


def option(node, pattern):
    regex = re.compile(pattern, re.I)
    options = [item for item in node.get("options", []) if regex.search(str(item.get("text", ""))) or regex.search(str(item.get("value", "")))]
    if len(options) != 1:
        raise Unbound(f'{len(options)} options of "{node.get("text")}" match /{pattern}/')
    return options[0]["value"]


def surface(spec, pattern):
    ids = [str(item) for item in spec.get("surfaces", {})] if isinstance(spec.get("surfaces"), dict) else []
    for item in controls(spec["ui"]):
        if item["node"].get("type") == "canvas":
            ids += [str(value) for value in item["node"].get("surfaces", [])]
    matches = sorted({value for value in ids if re.search(pattern, value, re.I)})
    if len(matches) != 1:
        raise Unbound(f"{len(matches)} surfaces match /{pattern}/: {sorted(set(ids))}")
    return matches[0]


def typed(node, value):
    """A text field sends text, as a person types it."""
    if node.get("type") == "input" and not isinstance(value, str):
        return str(int(value)) if isinstance(value, float) and value.is_integer() else str(value)
    return value


def steps(spec, operations):
    result = []
    for operation in operations:
        if isinstance(operation, dict):
            result.append(operation["raw"])
            continue
        kind, *args = operation
        if kind == "set":
            node = bind(spec, args[0], VALUE_TYPES, False)
            result.append({"set": node["action"], "value": typed(node, args[1])})
        elif kind == "choose":
            node = bind(spec, args[0], CHOICE_TYPES, False)
            result.append({"set": node["action"], "value": option(node, args[1])})
        elif kind == "tap":
            result.append({"tap": bind(spec, args[0], TAP_TYPES, False)["action"]})
        elif kind == "setRow":
            node = bind(spec, args[0], VALUE_TYPES | CHOICE_TYPES, True)
            result.append({"set": node["action"], "row": args[1], "value": typed(node, args[2])})
        elif kind == "tapRow":
            result.append({"tap": bind(spec, args[0], TAP_TYPES, True)["action"], "row": args[1]})
        elif kind == "wait":
            result.append({"wait": args[0]})
        elif kind == "pointer":
            result.append({"pointer": surface(spec, args[0]), "x": args[1], "y": args[2]})
        else:
            raise Unbound(f"Unknown operation {kind}")
    return result


NUMBER = re.compile(r"-?\d+(?:\.\d+)?")


def numbers(texts):
    values = []
    for text in texts:
        # Thousands separators: 2,450 and 2 450 are 2450. A decimal comma is a decimal point.
        text = re.sub(r"(?<=\d)[,   ](?=\d{3}\b)", "", str(text))
        text = re.sub(r"(?<=\d),(?=\d)", ".", text)
        values += [float(value) for value in NUMBER.findall(text)]
    return values


def judge(expectation, visible):
    kind, _, value = expectation.partition(":")
    texts = [str(text).lower() for text in visible]
    if kind == "num":
        wanted = float(value)
        decimals = len(value.split(".")[1]) if "." in value else 0
        tolerance = max(0.5 * 10 ** -decimals, abs(wanted) * 0.002) + 1e-9
        return any(abs(found - wanted) <= tolerance for found in numbers(visible))
    if kind == "text":
        return any(value.lower() in text for text in texts)
    if kind == "absent":
        return not any(value.lower() in text for text in texts)
    raise ValueError(f"Unknown expectation {expectation}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run", type=Path, required=True, help="A run_cases.py output directory.")
    parser.add_argument("--scenarios", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="A JSON file for the results.")
    parser.add_argument("cases", nargs="*")
    args = parser.parse_args()
    scenarios = json.loads(args.scenarios.read_text())
    wanted = args.cases or sorted(scenarios)
    results, jobs = {}, {}
    local = Path(tempfile.mkdtemp(prefix="app-scenarios-"))
    name = "app-scenarios/" + uuid.uuid4().hex
    for case in wanted:
        artifact = args.run / case.replace(":", "_") / "artifact.json"
        if not artifact.exists():
            results[case] = {"status": "no-app"}
            continue
        spec = json.loads(artifact.read_text())["spec"]
        bound, unbound = [], []
        for scenario in scenarios.get(case, []):
            try:
                raw = [item["raw"] for item in scenario.get("expect", []) if isinstance(item, dict)]
                bound.append({"name": scenario["name"], "steps": steps(spec, scenario["do"]) + raw})
            except Unbound as problem:
                unbound.append({"name": scenario["name"], "problem": str(problem)})
        jobs[case] = scenarios.get(case, [])
        results[case] = {"status": "pending", "unbound": unbound}
        file = local / (case.replace(":", "_") + ".input.json")
        file.write_text(json.dumps({"id": "check-" + case.replace(":", "-"), "spec": spec, "scenarios": bound}))
    env = child_environment()
    remote = f"{FILES}/{name}"
    subprocess.run(adb_command(ADB, "shell", "mkdir", "-p", remote, serial=args.serial), check=True, env=env)
    for file in local.iterdir():
        subprocess.run(adb_command(ADB, "push", str(file), remote + "/", serial=args.serial), check=True, env=env, capture_output=True)
    run = subprocess.run(adb_command(ADB, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST, "-e", "appScenarioDir", name,
                                     RUNNER, serial=args.serial), capture_output=True, text=True, env=env, timeout=3600)
    pulled = local / "pulled"
    subprocess.run(adb_command(ADB, "pull", remote + "/.", str(pulled), serial=args.serial), check=True, env=env, capture_output=True)
    subprocess.run(adb_command(ADB, "shell", "rm", "-rf", remote, serial=args.serial), env=env)
    for case, scenarios_for_case in jobs.items():
        output = pulled / (case.replace(":", "_") + ".result.json")
        if not output.exists():
            results[case].update(status="no-result", instrumentation=run.stdout[-1500:])
            continue
        outcomes = {item["name"]: item for item in json.loads(output.read_text())}
        checked = []
        for scenario in scenarios_for_case:
            outcome = outcomes.get(scenario["name"])
            if outcome is None:
                continue
            visible = outcome.get("visible", [])
            failed = [item for item in scenario.get("expect", []) if isinstance(item, str) and not judge(item, visible)]
            checked.append({"name": scenario["name"], "passed": outcome["passed"] and not failed,
                            "failed": failed, "failure": outcome.get("failure"), "screen": outcome.get("screen")})
        results[case]["scenarios"] = checked
        if "load" in outcomes:
            results[case].update(status="load-failed", failure=outcomes["load"].get("failure"))
        else:
            # Scenarios with the same "alt" name are alternatives: one of them must pass.
            # An unbound scenario counts as not passed. A group with only unbound scenarios is unbound.
            passed = {item["name"]: item["passed"] for item in checked}
            groups = {}
            for scenario in scenarios_for_case:
                groups.setdefault(scenario.get("alt", scenario["name"]), []).append(passed.get(scenario["name"]))
            if groups and all(any(group) for group in groups.values()):
                results[case]["status"] = "passed"
            elif any(all(value is None for value in group) for group in groups.values()):
                results[case]["status"] = "unbound"
            else:
                results[case]["status"] = "failed"
    shutil.rmtree(local, ignore_errors=True)
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    summary = {}
    for result in results.values():
        summary[result["status"]] = summary.get(result["status"], 0) + 1
    print(json.dumps(summary))
    for case, result in results.items():
        if result["status"] != "passed":
            detail = [item for item in result.get("scenarios", []) if not item["passed"]] + result.get("unbound", [])
            print(case, result["status"], json.dumps(detail, ensure_ascii=False)[:600])


if __name__ == "__main__":
    main()
