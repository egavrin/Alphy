#!/usr/bin/env python3
"""Validate the proposed supplemental comparison inputs against local Git objects."""
import json
from pathlib import Path
import sys
import unicodedata

sys.dont_write_bytecode = True
from compare import CORPUS_REVISION, SOURCES, add_inputs, git, load_corpus, make_plan


PROPOSAL = Path(__file__).with_name("reference-26eaacc-supplemental.proposed.json")
REFERENCE_REVISION = "26eaacccc8ec4ce5376fecedb0a6f821ccefd561"
CATEGORIES = {
    "itinerary", "quick-log", "dose-schedule", "due-items", "points-ledger", "idea-deck",
    "interval-timer", "reading-status", "itinerary-source-selection",
}


def main(repo):
    proposal = json.loads(PROPOSAL.read_bytes())
    assert proposal["status"] == "Proposed frozen inputs. Root acceptance is pending."
    assert proposal["reference"]["revision"] == REFERENCE_REVISION
    assert git(repo, "cat-file", "-t", REFERENCE_REVISION).strip() == b"commit"
    for case in proposal["cases"]:
        assert case["reference_files"]
        for path in case["reference_files"]:
            assert git(repo, "cat-file", "-t", f"{REFERENCE_REVISION}:{path}").strip() == b"blob"
    for path, _ in SOURCES.values():
        assert git(repo, "show", f"{CORPUS_REVISION}:{path}") == git(repo, "show", f"{REFERENCE_REVISION}:{path}")

    baseline = load_corpus(repo, CORPUS_REVISION)
    cases = load_corpus(repo, CORPUS_REVISION)
    capabilities, rubric = add_inputs(cases, PROPOSAL)
    assert cases[:121] == baseline
    supplemental = cases[121:]
    assert len(supplemental) == 9 and {case["category"] for case in supplemental} == CATEGORIES
    assert sum(case["language"] == "ru" for case in supplemental) == 8
    assert {cap for case in supplemental for cap in case["capabilities"]} == set(capabilities) == {"vibration.play"}
    negative = next(case for case in supplemental if case["category"] == "itinerary-source-selection")
    assert {"no_live_discovery", "no_invented_weather"} <= negative["interactions"].keys()

    requests = {unicodedata.normalize("NFKC", " ".join(case["request"].split())).casefold() for case in cases}
    assert len(requests) == len(cases) == len({case["id"] for case in cases}) == 130
    assert sum(case["split"] == "held-out" for case in cases) == 45
    fixture_execution = {"products": {"candidate": {"route": "fixture"}, "reference": {"route": "fixture"}}}
    plan = make_plan(cases, fixture_execution, rubric, capabilities, "final")
    assert len(plan["slots"]) == 780
    print("PASS: 9 proposed cases, 8 Russian requests, 54 supplemental slots, and unchanged 121 English inputs.")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
