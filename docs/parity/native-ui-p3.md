# Native mini-app UI

`ToolUiCatalog` is the native contract for generated UI. The validator, action boundary, model guidance, renderer, and widget projection use this catalog. The artifact format remains version 2. Existing saved state and record schemas need no conversion.

The catalog contains 34 native types. Compositions cover 55 applicable entries from the pinned reference inventory. This table records the functional mapping. It does not assert identical platform APIs or appearance.

| Reference construct | Native type or composition |
| --- | --- |
| Column | `column` |
| Row | `row`, with horizontal flow and action wrapping |
| Card | `card` |
| Scroll | `scroll`, with a bounded native viewport |
| Spacer | `spacer` |
| Grid | `grid` |
| AppTheme | `theme`, with `clean`, `soft`, `expressive`, `editorial`, `technical`, or `playful` |
| AdaptiveColumns | `grid`, which reduces columns at narrow widths |
| Hero, Header | `section` with headline `text`, `metric`, and optional `image` |
| Section | `section` |
| SectionHeader | `text` with title emphasis |
| ListGroup | `column` with `listItem` or a bound `list` |
| MetricGroup | `grid` with `metric` |
| TileGrid | `grid` with `card` |
| Tile, Cell | `card` or `listItem` with text and optional action |
| ActionBar | `row` with `button`, actionable `icon`, and optional `badge` |
| Timeline | `column` with ordered `listItem` and `divider` |
| TimelineItem | `listItem` with `icon`, `text`, and optional `metric` |
| KeyValueGroup | `column` or `card` with `row` and `text` |
| Button | `button` |
| TextField | `input`, with explicit Apply or keyboard commit |
| Toggle | `toggle` |
| Checkbox | `checkbox` |
| Slider | `slider`, with a local draft and explicit Apply |
| Select | `select` |
| Tabs | `tabs` |
| BackHandler | `backHandler`, active only for an enabled visible tree |
| NumberField | `numberInput` |
| TimeField | `timeInput`, with local minutes from midnight |
| DateField | `dateInput`, with ISO date strings |
| IconButton | `icon` with an action and accessible text |
| Text | `text` |
| Image | `image`, through an existing owner-scoped native resource handle |
| ListItem | `listItem` |
| Divider | `divider` |
| Progress | `progress` |
| ProgressRing | `progressRing` |
| InsetBanner, EmptyState | `card` with `icon`, `text`, and an optional `button` |
| Stat, NumberStat | `metric` |
| NumberText | `text` with number format |
| ClockText | `text` or `metric` with clock format |
| NumberListItem | `listItem` with `metric` |
| Icon | `icon` |
| Badge | `badge` |
| KeyValueItem | `row` with label `text` and bound `text` or `metric` |
| Table | `table` |
| TableRow | A native row from the table's bound records and columns |
| Skeleton | `skeleton` |
| Spinner | `spinner` |
| Snackbar | `snackbar` with an optional dismiss action |
| Sparkline | `chart` with line kind |

`Path`, `Canvas`, `Motion`, `Transform`, `Stage`, `Surface`, and `Ticker` remain P4 work. The generator cannot use these types. Internal chart drawing does not expose a graphics API to generated code.

Bindings traverse declared object fields in both schema and actual state. They do not traverse arrays or evaluate expressions. List and table column paths are relative to each record. A page record uses paths such as `value.name`. Selection sends a currently present stable `key` or legacy `id`. Controls send actions and never write state directly.

The UI limit is 256 structural nodes, depth 8, 50 children per container, and 32 KiB serialized JSON. Expanded rows, cells, options, and chart points also consume a shared 2048-unit render budget. Repeated bindings consume the budget again. At most four image nodes are allowed, including hidden nodes. Overflow rejects the complete UI. Tables allow 8 columns, 50 rows, and 400 cells. Charts allow 2 numeric series and 256 total points. Overflow fails validation. The existing 64 KiB sandbox boundary remains in force.

Image handles contain 64 lowercase hexadecimal characters. The native decoder checks ownership, artifact state, expiry, capability grants, runtime access, MIME type, and bounded bytes. It limits decoded dimensions through sampling and checks the actual allocation. Each image uses at most 4 MiB, with 1024-pixel edge limits and sRGB ARGB_8888 decoding. Four image nodes retain at most 16 MiB of decoded pixels per UI. Missing or revoked access produces a placeholder. Cached images clear when the screen pauses. Each resume checks access before display. A metadata check each second detects visible URI revocation, expiry, or resource removal without a state or grant-set change. These checks do not decode an available cached image again. Rendering requests no permission and opens no network URL.

Widgets preserve visible scalar state and valid local button or toggle actions. Advanced controls use a compact label that opens the exact mini-app. They do not display bound records, resource handles, or a page count as a global total. Scalar projections and native widget labels redact opaque resource handles. Hidden branches do not enter the projection. Existing lock and stale-token checks remain active.

The implementation check uses this command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

The check builds both APKs. It does not run instrumentation or prove rendered behavior. The initial implementation log is `/tmp/alphy-p3-compile.log`. The repair build log is `/tmp/alphy-p3-repair-compile.log`. The baseline revision is `cbf25f0ba107b9b028580a5a83235b6a5537427d` plus the existing dirty working tree and the P3 changes.

`NativeUiWorkflowTest` defines the boundary fixtures before the production changes. It covers the existing counter/list contract, a nested patient form, medication page selection, travel routes, charts, dates, times, forged actions, path failures, resource syntax, and limits. Existing capability-result tests cover resource ownership and denial. Existing surface tests cover hydration widget host dimensions and stale actions. Repair regressions cover aggregate expansion, repeated images, scalar handle redaction, and JUnit return types. `NativeImageLifecycleTest` uses the real lifecycle and native resource resolver to check pause, resume, URI denial, expiry, and removal. Its selected handle and bound state stay unchanged.

The root must run the relevant instrumentation classes on its selected emulator:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.alpha.assistant.tools.NativeUiWorkflowTest,com.alpha.assistant.tools.NativeImageLifecycleTest,com.alpha.assistant.tools.ToolWorkflowTest,com.alpha.assistant.tools.StructuredStateWorkflowTest,com.alpha.assistant.capabilities.CapabilityResultsWorkflowTest,com.alpha.assistant.surfaces.SurfaceWorkflowTest \
  com.alpha.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

For screenshots, use test-owned artifacts from `NativeUiFixtures`. Create them through an offline provider and the existing draft/accept workflow. Preserve existing user artifacts. The medical, medication, travel, and chart UI fixtures share one declared schema and state. Use their exact JSON rather than a model response. Keep the fixture tools separate from real patient and travel data.

Capture the medical form at narrow and wide widths, light and dark themes, and increased font scale. Focus the name field and capture the keyboard. Check field visibility, Apply controls, touch sizes, and scroll access. Capture the date and time dialogs and verify their committed values. Capture the medication table and select its stable key. Refresh the page and reject the prior state version.

Capture travel tables with horizontal scrolling. Capture both chart kinds with empty, constant, negative, and mixed values. Check the chart description through accessibility output. Capture an unavailable image and a selected native image with access, then revoke its grant and confirm the placeholder. Capture the hydration widget at its existing small and wide sizes. Capture an advanced widget summary and verify that it opens its exact artifact.

Use the emulator's configured serial for every device command. Save each screenshot and UI hierarchy with its fixture, revision, theme, font scale, size, expected result, and observed result. The root owns these acceptance artifacts. This implementation package did not access a device or capture screenshots.
