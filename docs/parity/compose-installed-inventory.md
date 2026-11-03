# Installed Compose inventory and acceptance baseline

This inventory compares the resolved Android toolkit with Alphy's bounded native mini-app interface.
The inventory ID is `compose-installed-20261103`. It records the pre-extension working tree above `62179af217478075a1d7a10960c949440dcc4ad8`.
Source hashes identify the uncommitted snapshot. Later implementation packages must record their own source identity and evidence.

Implementation choice: this inventory groups public APIs by meaningful workflow family. It does not count every overload or internal declaration.
The [machine inventory](compose-installed-inventory.json) contains exact coordinates, archive hashes, source paths, declaration lines, native contracts, gaps, and workflow criteria.
The [resolved dependency report](compose-dependencies-20261103.txt) supplies the complete dependency graph for this configuration.

## Baseline and status

Gradle resolved Compose BOM `2025.12.00`, Compose core/foundation/animation `1.10.0`, and Material 3 `1.4.0`.
It also resolved `activity-compose:1.12.2`, `lifecycle-runtime-compose:2.9.4`, and `savedstate-compose:1.3.2`.
The Compose compiler plugin is `2.4.0`. The app uses `minSdk 29`, `targetSdk 37`, and `compileSdk 37`.
[Application dependency declarations](../../app/build.gradle.kts) and [plugin declarations](../../build.gradle.kts) define those inputs.

The inventory includes 63 resolved coordinates from Compose and its adjacent activity, lifecycle, saved-state, and navigation-event groups.
The BOM supplies version constraints. A constraint or cached file alone does not count as an installed component.
The inventory excludes constraint-only rows and follows selected versions after `->`.
For each selected coordinate, the machine record retains local POM and binary hashes when present.
Common multiplatform coordinates can contain only metadata. Their Android variants supply the runtime binaries.

This package verified dependency resolution and source evidence. It performed no new rendered workflow, provider, device, accessibility-service, or physical measurement.
The [continuation evidence](../../evidence/compose-execution/continuation-20261103.md) records 262 instrumentation tests and incomplete real scenarios.
That earlier aggregate does not prove every inventory family or representative workflow.

| Status | Meaning |
| --- | --- |
| `verified` | The named check passed for its exact scope and snapshot. Here, this status applies to dependency and inventory integrity checks only. |
| `partial` | A native contract exists, with explicit missing behavior or incomplete workflow evidence. |
| `planned` | A bounded native extension is recommended. It is not callable in this snapshot. |
| `host_only` | Native host code uses or can use the facility. Generated artifacts cannot call its APIs. |
| `unavailable` | No current generated-artifact contract exists. This inventory claims no extension. |

Each family separately records `evidence_status` and `workflow_status`. Every representative workflow has status `not_run_for_this_inventory`.
A source declaration proves an installed API family. A matching native name does not prove complete properties, events, appearance, or performance.
Installed archives contain stable, deprecated, and experimental declarations. Each later extension must examine its selected overload and required opt-in.

## Resolved source evidence

Google Maven source archives supply the versioned primary evidence. The machine record stores archive SHA-256 and source-file SHA-256.
Each declaration record identifies its archive coordinate, internal source path, symbol, and declaration line.
No latest-version API page or unresolved cache entry supplies the baseline.

| Resolved artifact | Versioned primary source |
| --- | --- |
| `androidx.compose.foundation:foundation-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.10.0/foundation-android-1.10.0-sources.jar) |
| `androidx.compose.foundation:foundation-layout-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout-android/1.10.0/foundation-layout-android-1.10.0-sources.jar) |
| `androidx.compose.material3:material3-android:1.4.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar) |
| `androidx.compose.ui:ui-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.10.0/ui-android-1.10.0-sources.jar) |
| `androidx.compose.ui:ui-text-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.0/ui-text-android-1.10.0-sources.jar) |
| `androidx.compose.ui:ui-graphics-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics-android/1.10.0/ui-graphics-android-1.10.0-sources.jar) |
| `androidx.compose.animation:animation-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-android/1.10.0/animation-android-1.10.0-sources.jar) |
| `androidx.compose.animation:animation-core-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core-android/1.10.0/animation-core-android-1.10.0-sources.jar) |
| `androidx.compose.runtime:runtime-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.10.0/runtime-android-1.10.0-sources.jar) |
| `androidx.compose.runtime:runtime-saveable-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-android/1.10.0/runtime-saveable-android-1.10.0-sources.jar) |
| `androidx.compose.runtime:runtime-retain-android:1.10.0` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-retain-android/1.10.0/runtime-retain-android-1.10.0-sources.jar) |
| `androidx.activity:activity-compose:1.12.2` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.12.2/activity-compose-1.12.2-sources.jar) |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.9.4` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.9.4/lifecycle-runtime-compose-android-2.9.4-sources.jar) |
| `androidx.savedstate:savedstate-compose-android:1.3.2` | [Source archive](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-compose-android/1.3.2/savedstate-compose-android-1.3.2-sources.jar) |

The graph includes `material-ripple`. It does not include the Material 2 component module or Material icon packs.
It also excludes `navigation-compose`, Material 3 adaptive, `ui-viewbinding`, `ui-tooling`, `ui-tooling-preview`, and Compose UI test modules.
Existing Android instrumentation uses the AndroidX test runner and JUnit extension. That does not install Compose UI test rules.

## Family matrix

The table summarizes each family. The machine record contains properties, events, limits, exact source evidence, and acceptance criteria for every row.
`P1`, `P2`, and `P3` are recommendations for implementation order. They do not authorize host APIs or define a new product scope.

| Family ID | Native contract at baseline | Status | Specific gap |
| --- | --- | --- | --- |
| `layout-basic` | `column`, `row`, `grid`, `scroll`, `spacer` | `partial` | No Box/overlay node; row is FlowRow, not arbitrary RowScope. No weight or per-child alignment contract. |
| `layout-size` | `grid`, `scroll`, `transform`, `stage` | `partial` | No general bounded width/height, aspect ratio, constraints, inset policy, or responsive breakpoint contract. Host owns screen insets. |
| `collections-lazy` | `list`, `groupedList`, `table` | `partial` | No lazy viewport, scroll position, item visibility, or scroll command contract. Stable keys alone do not prove virtualization. |
| `collections-grid` | `grid` | `partial` | No bound grid item template, lazy grid, span, or staggered layout contract. |
| `collections-pager` | `tabs` | `planned` | Tabs do not expose swipe pages, snap behavior, or pager position. |
| `collections-groups` | `listGroup`, `groupedList`, `listItem`, `facts`, `fact`, `timeline`, `timelineItem`, `form`, `banner`, `emptyState` | `partial` | No sticky group headers, reorder gesture, swipe dismissal, or arbitrary ListItem slots. |
| `material-actions` | `button`, `icon` | `partial` | No button variant, FAB, extended FAB, or controlled loading button contract. Custom Alpha glyphs are not Material icon packs. |
| `material-chips` | Host/protocol facility only | `planned` | Assist, filter, input, and suggestion chips lack a native contract. |
| `material-choice` | `toggle`, `checkbox`, `select`, `tabs` | `planned` | No radio group, segmented choice, multi-select option set, or indeterminate checkbox state. |
| `material-ranges` | `slider`, `numberInput` | `partial` | No range pair, dual thumb contract, tick labels, or drag-finished event. |
| `material-containers` | `card`, `section`, `listItem`, `banner`, `emptyState`, `divider` | `partial` | No generic Surface type, arbitrary elevation, border, shape, or clickable Card contract. Existing named compositions do not expose all upstream slots. |
| `material-overlays` | Host/protocol facility only | `planned` | No declarative dialog, sheet, popup, or tooltip contract. Existing native review dialogs do not grant generated modal support. |
| `material-shell` | `row`, `tabs`, `backHandler` | `planned` | No mini-app scaffold, top/bottom app bar, navigation bar/rail/drawer, route stack, or scroll behavior contract. navigation type means maps capabilities. |
| `material-menu-tabs` | `select`, `tabs` | `partial` | No explicit menu groups, separators, menu item icons, or independent popup lifecycle. |
| `material-status` | `progress`, `progressRing`, `badge`, `skeleton`, `spinner`, `snackbar` | `partial` | No SnackbarHost queue/duration, indeterminate mode option, badge anchor, or animated placeholder contract. |
| `material-pickers` | `dateInput`, `timeInput` | `partial` | No Compose DatePicker/TimePicker style, date range, selectable-date policy, or time zone field. |
| `material-discovery` | `input`, `list`, `grid` | `planned` | No search suggestions/expanded state, carousel item state, or pull-refresh event contract. Some upstream declarations require experimental opt-in. |
| `text-display` | `text`, `metric`, `fact`, `timelineItem`, `badge` | `partial` | No maxLines, overflow, line height, selectable flag, span styles, or locale-format contract. |
| `text-rich-selection` | `text` | `planned` | No rich spans, inline validated links, selection container, or copy policy. Chat Markdown support does not expose mini-app rich text. |
| `text-editing` | `input`, `numberInput` | `partial` | No keyboard/IME choices, read-only/password mode, input transformation, selection/composition contract, or rich validation message. |
| `theme-colors` | `theme` | `partial` | No bounded custom palette, dark-mode selector, dynamic-color opt-in, or contrast preference contract. |
| `theme-type-shape` | `text`, `card`, `section` | `partial` | No bounded typography scale, corner choices, or per-component shape contract. |
| `input-gestures` | `scroll` | `partial` | No node tap/long-press/drag/zoom, nested-scroll policy, or drag/drop payload contract. Surface input does not expose arbitrary pointerInput. |
| `input-focus-ime` | `input`, `numberInput` | `partial` | No focus request/order/state, keyboard shortcuts, keyboard visibility, or IME action contract. |
| `input-clipboard-autofill` | Host/protocol facility only | `host_only` | No explicit clipboard read/write or autofill hint contract. Platform editing behavior is not an exposed host service. |
| `input-semantics` | `button`, `icon`, `toggle`, `checkbox`, `tabs`, `text`, `chart` | `partial` | No live region, stateDescription, traversal grouping/order, custom accessible action, or collection metadata contract. TalkBack workflow remains unrun here. |
| `graphics-drawing` | `chart` | `partial` | No arbitrary DrawScope, custom shaders, composable Canvas callbacks, or unrestricted clipping/effect objects. |
| `graphics-images` | `image`, `icon` | `partial` | No generated vector path asset, URL image fetch, animated image, custom painter, or Material icon-pack contract. |
| `graphics-effects` | `transform`, `stage` | `partial` | No blend mode, blur, RenderEffect, shadow, arbitrary graphicsLayer modifier, or runtime shader contract. |
| `motion-transitions` | `motion` | `partial` | No animated visibility/content replacement, crossfade, size transition, or bounded transition specification. |
| `motion-state` | `motion`, `transform`, `stage` | `partial` | No arbitrary easing/spring/keyframe/infinite transition contract. Fixed displayed frame rate and touch latency remain unverified. |
| `motion-shared` | Host/protocol facility only | `unavailable` | No bounded shared-element transition contract. |
| `runtime-state` | Host/protocol facility only | `host_only` | Compose snapshot APIs are host implementation facilities. Generated JavaScript globals are transient and cannot replace durable declared state. |
| `runtime-effects` | Host/protocol facility only | `host_only` | Compose effects are host-only. Native effect authority comes from validated foreground actions, not composition or timer presence. |
| `runtime-restoration` | Host/protocol facility only | `partial` | rememberSaveable/retain are not generated APIs. No general UI scroll/focus/draft restoration contract; pre-existing controller cancellation leak remains unresolved. |
| `android-back` | `backHandler` | `partial` | No predictive-back progress/cancel contract. BackHandler support does not expose predictive back APIs. |
| `android-results` | `calendar`, `medicine`, `navigation` | `partial` | Activity result plumbing is host-only. Each missing capability needs its own explicit adapter, authority, and result contract. |
| `android-lifecycle` | `image` | `host_only` | Host APIs are installed. Background subscriptions, process recreation, and image revocation each need workflow evidence. |
| `android-interop` | Host/protocol facility only | `host_only` | AndroidView is installed for native host code only. ui-viewbinding and WebView mini-app content are not exposed. |
| `developer-facilities` | Host/protocol facility only | `host_only` | No resolved ui-tooling, ui-tooling-preview, ui-test-junit4, ui-test-manifest, or Compose Material2 component module. Existing instrumentation does not imply Compose test-rule availability. |

## Native boundary

`ToolUiCatalog` defines the closed node catalog, bindings, style values, action validation, and model guidance.
[Catalog source](../../app/src/main/java/com/alpha/assistant/tools/ToolUiCatalog.kt) and [renderer source](../../app/src/main/java/com/alpha/assistant/tools/NativeToolUi.kt) supply the native mapping.
The inventory maps all 49 catalog types. It also records drawing surfaces, timers, frame subscriptions, and lifecycle events outside that type list.

The common UI bounds are 32 KiB, 256 structural nodes, depth 8, and 1–50 children per container.
Expanded records, options, and chart points share 2048 render-work units. Hidden content still consumes the validation budget.
Bindings traverse declared object fields. They do not evaluate expressions or array indices.
Actions validate the current visible, enabled node and its typed payload before execution.

The `row` node uses `FlowRow`. The `grid` node uses eager rows and adapts its column count.
The `list` and `groupedList` nodes use eager `Column` content with stable record keys.
The `select` node uses a native dropdown. The `tabs` node uses `ScrollableTabRow`.
The `dateInput` and `timeInput` nodes use Android platform dialogs. They do not expose Compose date/time picker APIs.
The `snackbar` node renders inline. It does not expose `SnackbarHost` queue behavior.

Graphics use validated protocol data. They do not expose a generated `DrawScope`, `Modifier`, `Painter`, shader, or Compose callback.
[Graphics contract](../../app/src/main/java/com/alpha/assistant/tools/GraphicsContract.kt) and [artifact protocol](../../app/src/main/java/com/alpha/assistant/tools/ArtifactProtocol.kt) define that boundary.
Frames and timers can update local state and drawing. They cannot authorize native effects or durable records.
Compose state, effects, saved-state owners, lifecycle objects, activity results, and Android views remain native host facilities.
Arbitrary composables, generated Kotlin, imports, Android objects, direct network access, and unrestricted host calls remain unavailable.

## Recommended slices and observable acceptance

These slices prioritize gaps that support many bounded mini-app workflows. The implementation owner must define each finite schema before production edits.
The root owns scope, interface decisions, integration, and acceptance. Recommendations do not remove required validation or native authority checks.

| Order | Bounded slice | Acceptance workflow and required observations |
| --- | --- | --- |
| 1 | Layout and readable text: `layout-basic`, `layout-size`, `text-display`, `text-rich-selection` | A form with a badge, long instructions, and validated links works at narrow width, landscape, large font, and keyboard open. Invalid dimensions and span ranges fail validation. |
| 2 | Typed choices and editing: `material-chips`, `material-choice`, `text-editing`, `input-focus-ime`, `input-semantics` | Select one option, select multiple filters, edit a note, and commit with IME. Current typed values, labels, focus, and disabled rejection remain correct after reopen. |
| 3 | Collections and local structure: `collections-lazy`, `collections-groups`, `material-overlays`, `material-shell` | Reorder stable records, edit one row in a bounded sheet, cancel another edit, change a local section, and return. Actions use current keys and correct modal focus. |
| 4 | Broader Material controls: range, menu, picker, status, search, pager, carousel, and refresh families | Each added control gets a typed event and finite properties. Cancel/dismiss preserves committed state. No search or refresh control grants network authority. |
| 5 | Graphics, motion, and lifecycle completion | Retain existing drawing budgets, reduced motion, accessible alternatives, pause/resume, and committed-state restoration. Measure physical timing only on suitable hardware. |

Every introduced node or property must reach validation, action dispatch, renderer, guidance, compact projection, and representative native fixtures.
Each slice needs its own reproducible evidence. The evidence must identify revision/source hashes, exact command and input, expected result, and actual result.
Use the [acceptance procedure](compose-acceptance.md) for the six canonical real-generation scenarios.
An accepted specification or screenshot does not replace the required interaction, persistence, provider, or authority observations.
Incomplete and failed earlier runs retain their status. This inventory does not select replacement passing runs.

## Reproduction

Run dependency resolution from the repository root. This command performs no device operation.

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath --console=plain > /tmp/alpha-compose-runtime.txt
```

Compare selected coordinates with `resolved_components`. Exclude `(c)` rows and use the selected version after `->`.
The recorded report contains `BUILD SUCCESSFUL` and no failed dependency.
This baseline covers `debugRuntimeClasspath`. It does not claim an APK binary audit, release shrinker result, or Android-test classpath inventory.

Fetch the exact source archives and verify their identity with this command:

```sh
python3 - <<'PY_CHECK_SOURCES'
from pathlib import Path
import hashlib, json, tempfile, urllib.request, zipfile
data = json.loads(Path("docs/parity/compose-installed-inventory.json").read_text())
with tempfile.TemporaryDirectory(prefix="alpha-compose-sources-") as directory:
    archives = {}
    for item in data["upstream_source_archives"]:
        payload = urllib.request.urlopen(item["url"], timeout=60).read()
        assert hashlib.sha256(payload).hexdigest() == item["sha256"], item["coordinate"]
        path = Path(directory) / item["url"].rsplit("/", 1)[-1]
        path.write_bytes(payload)
        archives[item["coordinate"]] = path
    for item in data["upstream_source_declarations"].values():
        with zipfile.ZipFile(archives[item["archive"]]) as archive:
            payload = archive.read(item["path"])
        assert hashlib.sha256(payload).hexdigest() == item["file_sha256"], item["path"]
        assert item["symbol"] in payload.decode().splitlines()[item["line"] - 1], item["symbol"]
print("PASS: 14 versioned archives and 103 source declarations")
PY_CHECK_SOURCES
```

Check the saved inventory locally with the focused command below:

```sh
python3 - <<'PY_CHECK_INVENTORY'
from pathlib import Path
import hashlib, json, re
root = Path.cwd()
data = json.loads((root / "docs/parity/compose-installed-inventory.json").read_text())
report = (root / data["dependency_report"]["path"]).read_bytes()
assert hashlib.sha256(report).hexdigest() == data["dependency_report"]["sha256"]
assert b"BUILD SUCCESSFUL" in report and not re.search(rb"\bFAILED\b", report)
selected = set()
for line in report.decode().splitlines():
    if " (c)" in line:
        continue
    match = re.search(r"(androidx\.(?:compose[^:]*|activity|lifecycle|savedstate|navigationevent)):(\S+?)(?::([\w.\-]+))? (?:-> ([\w.\-]+))?(?: |$)", line + " ")
    if match:
        group, artifact, requested, resolved = match.groups()
        if resolved or requested:
            selected.add(f"{group}:{artifact}:{resolved or requested}")
assert selected == {item["coordinate"] for item in data["resolved_components"]}
families = data["families"]
assert len({item["id"] for item in families}) == len(families) == 40
assert set(data["catalog_types"]) == {kind for item in families for kind in item["native_types"]}
assert all(item["workflow_status"] == "not_run_for_this_inventory" for item in families)
assert all(item["acceptance_criteria"] and item["gaps"] for item in families)
assert all(item["upstream"] and item["native_contract"]["source"] for item in families)
assert all(item["disposition"] in data["status_definitions"] for item in families)
assert all(item["evidence_status"] in data["status_definitions"] for item in families)
assert all(item["archive"] in selected for item in data["upstream_source_declarations"].values())
assert all(key in data["upstream_source_declarations"] for item in families for key in item["upstream"])
assert all(key in data["native_source_references"] for item in families for key in item["native_contract"]["source"])
assert not any(item.split(":")[0] + ":" + item.split(":")[1] in data["excluded_unresolved_modules"] for item in selected)
print(f"PASS: {len(families)} families; {len(selected)} resolved coordinates; 49 native types; no unrun workflow promoted")
PY_CHECK_INVENTORY
```

The [focused check record](compose-inventory-verification.txt) contains actual command scope, source identity, and observed results.
The source-hash map records a baseline. A later source change does not rewrite that historical snapshot or promote its workflow status.

## Open criteria and preserved records

The existing controller cancellation leak and navigation diagnostic defect remain outside this package. Their earlier permitted repair cycles are exhausted.
Physical displayed frame rate and touch-to-display latency remain unavailable without suitable hardware.
The real-generation matrix remains incomplete. The inventory does not promote accepted smoke runs to full scenario acceptance.
Historical OHOS records, `native-ui-p3.md`, `compose-coverage.md`, and `compose-coverage.json` retain their existing content and status.
This document supplements those records. It does not turn the 62 OHOS UI mappings into a full installed Compose inventory.
No new product-scope or architecture decision is required to read this baseline. Each later extension still needs an explicit bounded contract.
