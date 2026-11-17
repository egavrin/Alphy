# Shorter App Agent Reasoning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (the user chose native execution). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce the reasoning before the first app write with prompt changes that answer the agent's recurring doubts, fix layout defaults, and ask for a quick first version.

**Architecture:** Measure each prompt change first with raw DeepSeek calls on the dumped system prompt (reasoning tokens are exact and do not depend on provider speed). Implement only the variants that cut reasoning. Then confirm end to end with an order-balanced live run on the emulator.

**Tech Stack:** Kotlin (`AppAgentGuide.kt`), Python measurement scripts in the evidence folder, Android instrumented tests on emulator-5580.

**Spec:** No spec file. The requirement is the user's request in the session on 2026-11-17: clarify the platform rules that the reasoning doubts, fix layout planning, and ask for a simple first version. Evidence: `/Users/egavrin/Documents/alpha/alphy-agent-evidence-2026-11-17/reasoning-doubts/` (40 reasoning samples, 290 extracted doubts).

## Global Constraints

- Base is origin/main 892efa0. Branch `reasoning-rules`.
- Every fact in the prompt must match the code. Each new fact cites the code that proves it in the ledger.
- Controlled English for prompt text: short sentences, one term per item.
- Slopo and the architecture scan run before every commit. No push without the user's approval.

## Evidence summary (base prompt, 40 samples)

- Reasoning tokens: median 6118 (1671 to 23133).
- Doubt topics (count, returns): layout/components 44/59, timers/time 39/67, surfaces/canvas 38/45, actions/controls 34/43, binding/state 33/48, events 28/38, validation limits 25/30, checks/steps 23/31.
- Most doubts are about rules that the prompt already states in the dense UI catalog (row template payload, empty labels for bound text, hidden nodes). Some lines mislead: "When a request needs continuous updates, use a drawing surface with frame or timer subscriptions" suggests that timers need a surface.
- Protocol step 2 asks for a first version that is "correct and finished ... logic, layout, and visual design", which asks for full planning before the first write.

## Verified runtime facts

| Fact | Code |
|---|---|
| Source timers need a declared surface: the host rejects an app without a surface that returns timers. They tick while the app is resumed, not covered, and playback is playing (default). Missed ticks are not replayed. (Corrected after the final review: the first version said that timers run without a surface.) | `ToolGenerationSmoke` `widget_timer_host`; only the `NativeToolSurface.kt` loop delivers timers, and `ToolUi` mounts it only with surfaces or immersive; `clock.reset();timers.clear()`, `ArtifactPlayback.value="playing"` |
| The timer node sends no events and does not change state. A foreground timer has a fixed durationMs and host-owned alerts. | `ToolUiCatalog.kt` timer rule and validation |
| Only action events request effects. | `ArtifactProtocol.modelGuidance`, `AppAgentGuide.format` |
| A check wait ticks 50 ms, delivers subscribed frames and due timers, at most 600 events per wait. Each wait starts the timer intervals again. | `ToolGenerationSmoke.kt` `wait` (`due` is reset in each call), `MAX_PROBE_EVENTS=600` |
| A rowTemplate control sends `{key,value}`. Template action IDs are globally unique. | `ToolUiCatalog.kt` rowTemplate rule, `ToolGenerationSmoke.controls` |
| A bound text or badge may have an empty label. | `ToolUiCatalog.kt` general rules |
| Hidden nodes do not show, the smoke test skips them, text expectations do not see them, they count for limits. | `AppScreen.shownNode`, `ToolGenerationSmoke.controls`, catalog "Hidden nodes still validate" |
| A set step sends the action at once. | `AppSteps` Set, `ToolGenerationSmoke.control` |
| Pointer events carry surface units; check steps use fractions. Drawn text counts as visible text. | `ToolGenerationSmoke.pointer`, `AppScreen.surfaceText` |

## Review Focus

- A fact that is wrong for one presentation (inline card, full app, immersive game).
- A layout default that conflicts with an existing design rule or a guide example.
- A first-version instruction that raises the step count or the rejection count more than it saves.
- Tests that pin exact guide text and break.
- Prompt growth: facts add text, so the cached prompt grows.

---

### Task 1: Measure the prompt variants

**Files:** evidence folder only (`reasoning-doubts/variants/*.py`, `collect.py`, `score.py`).

- [ ] **Step 1:** Write variant transforms on the dumped system prompt: V1 runtime facts (and the corrected continuous-updates line), V2 layout defaults, V3 quick first version, V4 all three.
- [ ] **Step 2:** Collect 20 cases x 2 samples per variant with `collect.py`.
- [ ] **Step 3:** Score: reasoning tokens (median, paired geometric mean against base), share of replies that are a parseable `write_app` with ui and source.
- Expected: keep a variant only if its paired geometric mean is at most 0.8 and the parseable share does not drop. Also count first writes that return timers without a surface: the host rejects them.

### Task 2: Implement the kept variants

**Files:** Modify `app/src/main/java/com/alpha/assistant/tools/agent/AppAgentGuide.kt`. Test: a new `AgentGuideRuntimeFactsTest` (androidTest, tools package).

- [ ] **Step 1: Write the failing test.** It asserts the kept texts (for example the runtime facts heading and the 600-event wait rule) and the absence of the misleading continuous-updates line.
- [ ] **Step 2: Run it and see it fail.**
- [ ] **Step 3: Implement** the same texts that Task 1 measured.
- [ ] **Step 4: Run** the new test, `AgentGuideExampleTest`, `AgentGenerationWorkflowTest`, `GenerationContractGuidanceTest`. Expected: pass.
- [ ] **Step 5: Commit** after Slopo and the architecture scan.

### Task 3: Confirm on the real prompt and live

- [ ] **Step 1:** Dump the real system prompts on the device for base 892efa0 and the head. Run the raw API A/B again (20 cases x 2). Expected: the Task 1 effect holds.
- [ ] **Step 2:** Run the 10 timing cases live in ABBA order on emulator-5580. Compare outcome, steps, rejections, first-step wait, and chat time with paired ratios.
- [ ] **Step 3:** Report. Push only with the user's approval.
