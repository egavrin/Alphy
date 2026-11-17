#!/usr/bin/env python3
"""Score saved mini-apps from live corpus runs for app look and self-contained games.

Usage:
  visual_score.py RUN_DIR [RUN_DIR...] [--against BASE_DIR[,BASE_DIR...]]
  visual_score.py --self-test

Each RUN_DIR holds one directory per case with artifact.json, and summary-*.jsonl
files from run_cases.py. A case that occurs in more than one RUN_DIR takes the
last one. Case tags come from showcase.json and holdout.json next to this script.
"""
import json
import re
import statistics
import sys
from pathlib import Path


HERE = Path(__file__).resolve().parent
SETS = {
    "mock": ["showcase-3d-dna", "showcase-image-flashcards", "showcase-image-menu", "showcase-image-plant",
             "showcase-image-recipe", "showcase-lang-budget-es", "showcase-lang-habits-uk", "showcase-lang-kanji-ja",
             "showcase-lang-shopping-it", "showcase-lang-units-pl", "showcase-lang-water-ko", "showcase-lang-workout-de",
             "showcase-ui-alarm", "showcase-ui-price-filter", "showcase-ui-priorities"],
    "games": ["showcase-game-snake", "showcase-game-memory", "showcase-game-2048", "showcase-game-drawing",
              "showcase-3d-dice", "showcase-3d-maze"],
}
FIELD_TYPES = {"input", "numberInput", "timeInput", "dateInput", "dateRangeInput"}
HIDDEN_CONTAINERS = {"dialog", "bottomSheet"}
FIRST_CHILD_ONLY = {"pager", "routeStack"}
CHROME = {"appBar", "bottomBar", "navigationBar", "navigationRail", "spacer", "divider"}
BUTTON_TYPES = {"button", "actionChip", "icon"}
# Words of native direction and restart controls in the corpus languages.
GAME_CONTROL_WORDS = [
    "left", "right", "up", "down", "restart", "play again", "new game", "start", "arrow",
    "links", "rechts", "oben", "unten", "neustart", "neues spiel", "nochmal",
    "izquierda", "derecha", "arriba", "abajo", "reiniciar", "nueva partida", "empezar",
    "gauche", "droite", "haut", "bas", "recommencer", "rejouer", "nouvelle partie",
    "sinistra", "destra", "giù", "ricomincia", "nuova partita",
    "влево", "вправо", "вверх", "вниз", "заново", "новая игра", "начать",
    "左", "右", "上", "下", "重新", "再来", "开始", "왼쪽", "오른쪽", "다시", "시작",
]
# Gradient fill, object color, or a soft shadow: a black with alpha other than FF.
DEPTH_SOURCE = re.compile(r"gradient\s*:|color\s*:\s*\{\s*type|#(?!ff)[0-9a-f]{2}000000", re.I)
HIGHER = ["hero", "picture", "immersive", "viewport", "depth", "tilt"]
LOWER = ["fields", "rowFields", "nativeGameButtons"]


def _children(node):
    kids = node.get("children") or []
    return [k for k in kids if isinstance(k, dict)]


def _visible(node, state):
    bind = node.get("visibleBind")
    return not bind or bool(state.get(bind))


def _walk(node, state, first_screen):
    """Yield (node, in_template) for nodes; first_screen skips hidden and later pages."""
    stack = [(node, False)]
    while stack:
        n, in_template = stack.pop()
        if first_screen and (n.get("type") in HIDDEN_CONTAINERS or not _visible(n, state)):
            continue
        yield n, in_template
        kids = _children(n)
        if first_screen and n.get("type") in FIRST_CHILD_ONLY:
            kids = kids[:1]
        stack.extend((k, in_template) for k in kids)
        template = n.get("rowTemplate")
        if isinstance(template, dict) and not first_screen:
            stack.append((template, True))


def _is_hero(node):
    t, style = node.get("type"), node.get("style") or {}
    return (t == "card" and node.get("variant") == "hero"
            or t == "progressRing" and node.get("size") == "large"
            or t == "progress" and node.get("variant") in ("level", "dots")
            or t in ("image", "canvas")
            or t == "emoji" and node.get("size") == "large"
            or t in ("metric", "text") and style.get("emphasis") == "display")


def _has_hero(node, depth=0):
    return _is_hero(node) or depth < 2 and any(_has_hero(k, depth + 1) for k in _children(node))


def _top_nodes(ui):
    node = ui
    while node.get("type") in ("theme", "scroll", "column") and len(_children(node)) == 1:
        node = _children(node)[0]
    return [k for k in _children(node) if k.get("type") not in CHROME][:2] or [node]


def _game_control(node):
    if node.get("type") not in BUTTON_TYPES or node.get("type") == "icon" and not node.get("action"):
        return False
    words = " ".join(str(node.get(k, "")) for k in ("text", "icon", "name", "action"))
    words = re.sub(r"([a-z])([A-Z])", r"\1 \2", words).replace("_", " ").replace("-", " ").lower()
    return any(re.search(rf"\b{re.escape(w)}\b" if w.isascii() else re.escape(w), words) for w in GAME_CONTROL_WORDS)


def score_app(spec, tags):
    ui, state = spec.get("ui") or {}, spec.get("initialState") or {}
    immersive = (spec.get("presentation") or {}).get("mode") == "immersive"
    surfaces = spec.get("surfaces") or []
    all_nodes = list(_walk(ui, state, False))
    s = {
        "hero": int(immersive or any(_has_hero(n) for n in _top_nodes(ui))),
        "fields": sum(n.get("type") in FIELD_TYPES for n, _ in _walk(ui, state, True)),
        "rowFields": sum(n.get("type") in FIELD_TYPES for n, t in all_nodes if t),
    }
    if "image" in tags:
        s["picture"] = int(any(n.get("type") == "image" for n, _ in all_nodes))
    if "game" in tags:
        movement = (spec.get("hostControls") or {}).get("movement") or []
        s["immersive"] = int(immersive)
        s["viewport"] = int(any(x.get("sizing") == "viewport" for x in surfaces) or immersive and "runtime" in spec)
        s["depth"] = int(bool(surfaces or spec.get("runtime")) and bool(DEPTH_SOURCE.search(str(spec.get("source") or ""))))
        s["nativeGameButtons"] = sum(_game_control(n) for n, _ in all_nodes) + 2 * len(movement)
    if "tilt" in tags:
        s["tilt"] = int("sensor.accelerometer.stream" in json.dumps(spec.get("capabilities") or []))
    return s


def corpus_tags():
    tags = {}
    for name in ("showcase.json", "holdout.json"):
        for case in json.loads((HERE / name).read_text())["cases"]:
            tags[case["id"]] = case.get("tags") or []
    return tags


def load_runs(dirs):
    """Return {case: {"score": dict|None, "summary": dict}} merged over dirs in order."""
    tags, runs = corpus_tags(), {}
    for d in map(Path, dirs):
        for summary in sorted(d.glob("summary-*.jsonl")):
            for line in summary.read_text().splitlines():
                if not line.strip():
                    continue
                row = json.loads(line)
                artifact = d / row["case"] / "artifact.json"
                score = None
                if row.get("outcome") == "artifact-committed" and artifact.exists():
                    score = score_app(json.loads(artifact.read_text())["spec"], tags.get(row["case"], []))
                runs[row["case"]] = {"score": score, "summary": row}
    return runs


def set_cases(name, runs):
    if name == "holdout":
        return [c["id"] for c in json.loads((HERE / "holdout.json").read_text())["cases"]]
    return SETS[name] if name in SETS else sorted(runs)


def totals(runs, cases):
    present = [runs[c] for c in cases if c in runs]
    t = {"cases": len(present), "committed": sum(r["score"] is not None for r in present)}
    for key in HIGHER + LOWER:
        values = [r["score"][key] for r in present if r["score"] and key in r["score"]]
        if values:
            t[key] = sum(values)
    seconds = [r["summary"].get("seconds") or 0 for r in present]
    steps = [r["summary"].get("steps") or 0 for r in present]
    if present:
        t.update(medianSeconds=statistics.median(seconds), totalSeconds=sum(seconds),
                 medianSteps=statistics.median(steps))
    return t


def verdict(base, new):
    """Pass rule: visual criteria none worse and total better; commits not lower; time and steps not worse than 10%."""
    worse, better = [], []
    for key in HIGHER + LOWER:
        if key in base and key in new and base[key] != new[key]:
            improved = new[key] > base[key] if key in HIGHER else new[key] < base[key]
            (better if improved else worse).append(key)
    if new["committed"] < base["committed"]:
        worse.append("committed")
    for key in ("medianSeconds", "medianSteps"):
        if key in base and new.get(key, 0) > base[key] * 1.1:
            worse.append(key)
    return ("PASS" if better and not worse else "FAIL"), better, worse


def main(argv):
    against = []
    if "--against" in argv:
        i = argv.index("--against")
        against = argv[i + 1].split(",")
        argv = argv[:i] + argv[i + 2:]
    runs = load_runs(argv)
    keys = HIGHER + LOWER
    print("case\t" + "\t".join(keys) + "\tseconds\tsteps")
    for case in sorted(runs):
        score, row = runs[case]["score"], runs[case]["summary"]
        cells = ["-" if not score or k not in score else str(score[k]) for k in keys]
        print("\t".join([case] + cells + [str(row.get("seconds")), str(row.get("steps"))]))
    base_runs = load_runs(against) if against else None
    for name in ("mock", "games", "holdout"):
        cases = set_cases(name, runs)
        if not any(c in runs for c in cases):
            continue
        new = totals(runs, cases)
        print(f"TOTAL {name}: {json.dumps(new)}")
        if base_runs is not None:
            base = totals(base_runs, cases)
            result, better, worse = verdict(base, new)
            print(f"  base {name}: {json.dumps(base)}")
            print(f"  {result} better={better} worse={worse}")


def self_test():
    hero = {"initialState": {}, "ui": {"type": "theme", "children": [{"type": "column", "children": [
        {"type": "card", "variant": "hero", "children": [{"type": "metric", "text": "Total", "bind": "t"}]},
        {"type": "numberInput", "text": "Bill", "bind": "bill"}]}]}}
    s = score_app(hero, [])
    assert (s["hero"], s["fields"], s["rowFields"]) == (1, 1, 0), s
    form = {"initialState": {}, "ui": {"type": "column", "children": [
        {"type": "input", "text": "Name", "bind": "name"},
        {"type": "list", "bind": "rows", "rowTemplate": {"type": "row", "children": [
            {"type": "input", "text": "Note", "bind": "note"}]}}]}}
    s = score_app(form, [])
    assert (s["hero"], s["rowFields"]) == (0, 1), s
    game = {"initialState": {}, "surfaces": [{"id": "board", "width": 300, "height": 300}],
            "ui": {"type": "column", "children": [{"type": "canvas", "surfaces": ["board"]},
                                                  {"type": "button", "text": "Left", "action": "left"}]}}
    s = score_app(game, ["game"])
    assert (s["nativeGameButtons"], s["immersive"]) == (1, 0), s
    game["ui"]["children"][1] = {"type": "button", "text": "Update", "action": "moveLeft"}
    assert score_app(game, ["game"])["nativeGameButtons"] == 1
    game["ui"]["children"][1] = {"type": "button", "text": "Update", "action": "setup"}
    assert score_app(game, ["game"])["nativeGameButtons"] == 0
    lit = {"initialState": {}, "surfaces": [{"id": "board", "width": 300, "height": 300}], "ui": {"type": "column"},
           "source": "c.push({type:'circle',x:1,y:1,radius:2,gradient:{type:'radial',x:1,y:1,radius:2,stops:[]}})"}
    assert score_app(lit, ["game"])["depth"] == 1
    lit["source"] = "c.push({type:'rect',color:'#FF000000'});c.push({type:'rect',color:'#80000000'})"
    assert score_app(lit, ["game"])["depth"] == 1
    lit["source"] = "c.push({type:'circle',x:1,y:1,radius:2,color:'#FF0000'});c.push({type:'rect',color:'#FF000000'})"
    assert score_app(lit, ["game"])["depth"] == 0
    lit["surfaces"] = []
    lit["source"] = "x={gradient:{}}"
    assert score_app(lit, ["game"])["depth"] == 0
    assert verdict({"committed": 1, "depth": 0}, {"committed": 1, "depth": 1}) == ("PASS", ["depth"], [])
    print("self-test ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) > 1:
        main(sys.argv[1:])
    else:
        sys.exit(__doc__)
