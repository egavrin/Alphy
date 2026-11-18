# App file kit: timer evidence

This file records the device proof of Task 6 of `docs/superpowers/plans/2026-11-18-app-file-kit.md`.
It answers the two open points of section 8 of `docs/superpowers/specs/2026-11-18-app-file-parts-design.md`.

## Runs

All runs used the emulator `emulator-5592` (AVD `AlphyAppFile`, API 36) and the class `com.alpha.assistant.tools.appfile.AppFileProofTest`.
The `run.json` of each label is in `evidence/alphy-ui/<label>/`, which Git does not keep.

| Label | Revision | Targets | Expected | Observed |
| --- | --- | --- | --- | --- |
| `appfile-timer-red` | `fb72fb3+dirty` | `teaPassesParseAndSmoke`, `timerRoundsPassesParseAndSmoke` | failed | failed: "need demleme: timer is not built yet" and "need timer: timer is not built yet" |
| `appfile-timer` | `fb72fb3+dirty` (the content of this commit) | the class, 16 tests | passed | passed, `OK (16 tests)`, 28.6 s |

Command of the last run:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 tools/compose_acceptance/run.py suite --serial emulator-5592 --label appfile-timer com.alpha.assistant.tools.appfile.AppFileProofTest
```

The test APK `lastUpdateTime` was `2026-11-18 20:45:47` before the run and `2026-11-18 20:47:42` after it.
No other session installed the test APK during the run.

## Open point 1: a duration that a choice gives

The plan `kit/plans/tea.plan` gives the countdown a formula: 5, 3, or 7 minutes for the chosen tea.
The kit maps the need to Alphy's foreground timer: `{type: "timer", mode: "countdown", action, foreground: {durationMs, vibrationMs: 600, toneMs: 600}}`.
The definition declares the capabilities `audio.tone` and `vibration.play`.

`teaPassesParseAndSmoke` observed these results:

- `ToolSpec.parse` and the smoke step accepted the definition.
- Before a choice, `ToolUiCatalog.validateForegroundTimer` read 300000 ms.
- After the choice "Yeşil çay", `ToolGenerationService.executeEvent` accepted the reply with a new `ui`, and the timer read 180000 ms.
- After the choice "Bitki çayı", the timer read 420000 ms.

Answer: yes. The source may return a `ui` with a new duration after the choice changes, and Alphy's validator takes the new duration.
So the timer part needs no `outside("timer")` rule for a time from a choice.

## Open point 2: no effect from a timer event

A timer with rounds or a rest keeps the end of its phase in the state (`NAME_s.end`, in ms of the host's clock).
Its bar is one surface of 320 by 64 in a `canvas` node.
While it runs, each reply asks for `timers: [{id: "NAME_bar", intervalMs: 1000}]`, and a stop gives `timers: []`.
A timer event cannot ask for an effect, so this form gives no sound and no vibration.
The kit's report has the note "need timer: a timer with rounds shows the end of a round on the screen only", and no fault.
The kit test `check: alert with rounds is a note, not a fault` holds this result.

`timerRoundsPassesParseAndSmoke` observed these results:

- `kit/plans/timer-rounds.plan` passed `ToolSpec.parse` and the smoke step, with no `widget_timer_host` failure.
- The smoke step sent the timer `timer_bar` (its replay held a `timer` event with that id).
- A plan of 1 s work and 1 s rest in 2 rounds went through the host's path of an open app on the real clock.
  The path was one retained `GraphicsSession`, `executeEvent`, and `dispatch` for each event.
- Each timer event came 300 ms after the end of the phase in the state of the reply before it.
- The four replies were: rest of round 1 with 1 finished round, work of round 2, rest of round 2 with 2 finished rounds, and the end with `timers: []`.
- Five timer events of the 40 s work phase of `timer-rounds.plan` took `[3, 4, 4, 2, 3]` ms, median 3 ms (limit 50 ms).

Answer: the canvas form works on the device, so the timer part needs no `outside("timer")` rule for rounds.

## Times of the last run

| Measure | Samples (ms) | Median (ms) |
| --- | --- | --- |
| Timer event through the host's path | 3, 4, 4, 2, 3 | 3 |
| Compile of `quiz.plan` | 29, 29, 30, 28, 29 | 29 |
| Event of `calc.plan` | 9, 9, 8, 9, 9 | 9 |
| Add of `tabs-chart.plan` | 10, 10, 11, 9, 11 | 10 |
| Event of `rich.plan` | 20, 20, 20, 20, 20 | 20 |
| Event of `cards.plan` | 20, 18, 19, 18, 18 | 18 |
| Event on 100 rows | 15, 18, 16, 18, 17 | 17 |

## What the device did not show

- No test heard a sound or felt a vibration at the end of a countdown.
- No test saw a notification. The kit asks for no notification.
- No test pressed the host's own Start, Pause, or Reset button of a foreground timer.
  So no test saw the host's countdown run with the new duration. The proof stops at Alphy's validator and parser of the reply.
- No test took a screenshot of the canvas. The drawing commands passed `GraphicsContract`, and nobody looked at the bar.
- No test sent a timer event with a playback state other than `playing`.
- No test closed the app while a timer ran. Only a kit test shows that an open moves a timer to the clock of now.
