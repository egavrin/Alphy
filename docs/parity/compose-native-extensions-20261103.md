# Native Compose extensions and remaining coverage

This record supplements `compose-installed-20261103`. It preserves the earlier inventory and historical evidence.
The finite native catalog now exposes Material controls, interactions, transitions, and the preceding layout/text extensions.
Each family below names its implemented subset and remaining gap. No row claims full upstream API parity.
The [machine record](compose-native-extensions-20261103.json) retains source hashes and the complete 40-family mapping.

## Actual verification

`./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` completed with `BUILD SUCCESSFUL`.
The [saved check evidence](compose-native-extensions-check.txt) records the revision, command, inputs, expected result, and observed result.
Production and instrumentation sources compiled. This package ran no device, provider, rendered workflow, TalkBack, or physical measurement.
The new fixtures are compiled acceptance inputs. Their workflow assertions remain unrun by this package.
The root owns read-only review, integration, device acceptance, and real-generation acceptance.

## Public contract

- Choices use finite typed options. Multi-choice events contain a unique complete array, including an empty selection.
- Numeric ranges contain two ordered bounded values. Date ranges contain two real ordered ISO dates in years 0001..9999.
- Buttons expose seven native variants. Chips expose assist, suggestion, filter, and input controls with explicit remove actions.
- App bars retain native children. Dialogs and sheets require a declared visibility binding and a unique dismiss action.
- Pager events contain integer indices for 1..10 pages. Only current-page children can dispatch actions.
- Interactions expose click, long press, double click, focus changes, and seven finite keys. Semantics use bounded native properties.
- Long and double gestures provide labeled accessibility actions. Swipes retain the same visible row action as an accessible alternative.
- Lazy lists expose sticky groups. Lazy grids expose groups and validated spans. Stable keys remain mandatory.
- Visibility transitions use three finite presets and bounded duration. Reduced motion removes transition duration.
- Widgets use exact-app summaries for advanced controls, interactions, modals, pages, transitions, and record collections.
- Existing JSON, depth, node, expanded-work, image, schema, action, and capability limits remain in force.
- Generated code receives no Kotlin, composable, modifier, Android API, network, file, or privilege access from these controls.

`NativeMaterialWorkflowTest` records failure cases before the related production edits.
Its boundary fixtures reject forged choices, duplicate values, malformed ranges, inactive page actions, hidden modals, duplicate IDs, and unsupported properties.
Its offline rendered workflow covers choice, dialog confirmation, pager change, durable state, and reopen.
Its smoke workflow checks range limits, empty multi-selection, focus events, and one newly revealed modal or page step.
The smoke sampler stops after one reveal step. It does not explore an arbitrary action graph.
`NativeLayoutTextWorkflowTest` supplies the preceding layout, text, theme, and collection fixtures.

## All installed families

`partial` means the listed finite interface exists, with the stated gaps. It does not mean its rendered workflow passed.
`host_only` identifies a host facility. `unavailable` identifies a missing generated contract.

| Inventory family | Status | Implemented finite interface | Remaining gap |
| --- | --- | --- | --- |
| `layout-basic` | partial | column, nonwrapping row, flowRow, box overlay, adaptive grid, scroll; parent weight/alignment | No arbitrary layout callbacks, custom measurement, or ConstraintLayout API. |
| `layout-size` | partial | Bounded dimensions, constraints, aspect ratio, finite insets and arrangement | No breakpoint expressions, intrinsic measurement, or arbitrary modifier chain. |
| `collections-lazy` | partial | lazyList, stable keys, bounded viewport, selected/scroll target key | No item-visibility callbacks, prefetch policy, or unbounded collections. |
| `collections-grid` | partial | lazyGrid, responsive column count, rowTemplate, groupBy, validated spanField | No staggered grid or arbitrary span callback. |
| `collections-pager` | partial | Native HorizontalPager, bounded page index event, previous/next actions, current-page action authority | No infinite pager, fractional scroll callback, or route stack. |
| `collections-groups` | partial | groupBy, lazyList stickyHeaders, lazyGrid full-width group headers, swipeAction references visible rowActions | No free drag reorder or drag/drop. Explicit row actions can reorder script state with stable keys. |
| `material-actions` | partial | Native filled/outlined/text/tonal/elevated buttons, FAB and extended FAB, bounded glyphs | No loading-button state machine or Material icon pack. Disabled FAB uses a disabled tonal button. |
| `material-chips` | partial | actionChip assist/suggestion variants; boolean chip filter/input variants; optional remove action | No avatar or arbitrary leading/trailing slots. Input-chip removal remains a script state decision. |
| `material-choice` | partial | singleChoice radio group, segmented single choice, multiChoice complete typed selected array, toggle and checkbox | No tri-state checkbox or multi-select segmented row. |
| `material-ranges` | partial | rangeSlider with two ordered bounded numbers and optional step, Apply event | No tick-label formatter, drag stream, or distinct drag-finished event. |
| `material-containers` | partial | card, section, listItem, semantic containers, theme-scoped bounded shapes | No generated Surface, arbitrary elevation, border, or slot functions. |
| `material-overlays` | partial | Script-controlled dialog/bottomSheet with visibleBind, unique dismiss action, validated child controls | No arbitrary popup, tooltip, modal stack, or persistent sheet offset. |
| `material-shell` | partial | Native appBar/bottomBar, typed navigationBar/navigationRail; host retains scaffold and authority | No navigation drawer, generated route stack, collapsible app-bar scroll behavior, or independent system bars. |
| `material-menu-tabs` | partial | Typed native menu/select popup, tabs, segmented choices | No menu groups, separators, item icons, or script-controlled popup position. |
| `material-status` | partial | Determinate/indeterminate linear/ring progress, spinner, badge, snackbar, banner, skeleton | No SnackbarHost queue/duration, anchored badge layout, or shimmer policy. |
| `material-pickers` | partial | Native date/time dialogs; Material date-range dialog emits ordered ISO pair, years 0001..9999 | No selectable-date predicate, picker style selector, time-zone field, or Material time-picker option. |
| `material-discovery` | partial | Bounded search input and Search/IME event; pager supplies bounded swipe pages | No suggestions contract, expanded SearchBar, carousel, or pull-refresh event. |
| `text-display` | partial | Bounded line count/height, overflow, wrap, selectable text, scalar formats | No generated locale formatter or downloadable font policy. |
| `text-rich-selection` | partial | Static ordered rich spans, SelectionContainer, validated link actions through existing capability broker | No inline composable content, editable rich text, or generated clipboard service. |
| `text-editing` | partial | input/search keyboard/IME choices, maxLines, password masking, explicit Apply/Search; numeric fields | No read-only flag, transformation callbacks, selection/composition API, or custom validation message contract. Password masking does not encrypt state. |
| `theme-colors` | partial | Preset and system/light/dark mode; bounded opaque color pairs with contrast validation | No dynamic-color opt-in, arbitrary color role, or user contrast-preference API. |
| `theme-type-shape` | partial | Bounded theme typeScale/cornerRadius; host font scaling retained | No generated font files, arbitrary TextStyle, or per-component custom shape. |
| `input-gestures` | partial | Node click/longClick/doubleClick; collection horizontal swipe; pager swipe; labeled accessibility alternatives | No node drag stream, zoom, drag/drop payload, arbitrary pointerInput, or generated nested-scroll policy. |
| `input-focus-ime` | partial | Focus-state boolean event, finite key shortcuts, native focusability, input keyboard/IME choice | No focus request/order API, keyboard show/hide command, or draft selection restoration. |
| `input-clipboard-autofill` | host_only | Platform editing and text selection retain native host behavior | No generated clipboard read/write, autofill hints, or credential service. |
| `input-semantics` | partial | Label, heading, stateDescription, polite/assertive liveRegion; long/double custom accessibility actions; native choice/range semantics | No explicit traversal order/group, custom role enumeration, or collection metadata API. TalkBack workflow remains unrun. |
| `graphics-drawing` | partial | Existing validated native drawing-surface command contract and scoped input | No arbitrary DrawScope, shader source, composable Canvas callbacks, or host drawing code. |
| `graphics-images` | partial | Owner-scoped opaque resource handles, bounded image decode, lifecycle/revocation checks, bounded glyph set | No URL image fetch, generated vector paths, animated image, custom painter, or Material icon pack. |
| `graphics-effects` | partial | Bounded transform pose, opacity, size and shared stage perspective | No generated blur, RenderEffect, blend mode, runtime shader, or arbitrary graphicsLayer. |
| `motion-transitions` | partial | transition boolean visibility with fade/fadeSlide/expand, bounded duration, reduced-motion zero duration, immediate hidden-action rejection | No keyed content replacement or generic crossfade/shared-content identity contract. |
| `motion-state` | partial | Existing finite decorative motion presets and eight-value transform interpolation | No arbitrary easing, spring, keyframe, or infinite transition. Physical frame rate and touch latency remain unmeasured. |
| `motion-shared` | unavailable | No generated shared-element transition contract | A future bounded shared-element identity and lifecycle design needs separate planning. |
| `runtime-state` | host_only | Declared validated durable artifact state; host controls Compose snapshot state | No generated snapshot/remember/derivedStateOf API or durable JavaScript globals. |
| `runtime-effects` | host_only | Existing broker, subscriptions and validated effects; host controls Compose effects | Composition, focus, gestures, and control presence grant no native privilege or automatic capability authorization. |
| `runtime-restoration` | partial | Durable artifact state; native lazy and pager state; declarative selected/page/scroll targets | No general focus/draft/offset restoration guarantee. Known controller cancellation defect remains outside this package. |
| `android-back` | partial | Route stack Back and native modal dismissal | No generated predictive-back progress/cancel contract. |
| `android-results` | host_only | Existing capability-specific result adapters retain host ownership | Missing native services require explicit capability, authority and result contracts. |
| `android-lifecycle` | host_only | Host lifecycle controls images, sandbox subscriptions and native composition | Process-death, background subscriptions, and revocation require device workflow acceptance. |
| `android-interop` | host_only | AndroidView remains a native host implementation facility | No generated AndroidView, WebView, view-binding, arbitrary Android API, or host code. |
| `developer-facilities` | unavailable | Existing Android instrumentation compiles with the application | No resolved Compose tooling/test-rule modules or Material2 contract. Instrumentation compilation does not prove rendered execution. |

## Preserved limits and open acceptance

No dependency upgrade, arbitrary modifier API, legacy adapter, data migration, provider call, or device operation was added.
The known controller cancellation and navigation diagnostic defects remain outside this package. Medical scope remains unresolved.
Physical displayed frame rate and touch-to-display latency still require suitable hardware.
Device acceptance must check narrow width, large font, reduced motion, keyboard focus, gesture cancellation, modal dismissal, and accessibility alternatives.
Date-range rendering across supported years and dynamic pager updates also require device acceptance.
No unrun behavior in this record has status `verified`.

## Focused repair

Review found that Float bounds could widen a decimal range endpoint beyond its declared Double bound.
The renderer now stores Double drafts and gives Compose unit fractions. Endpoint events preserve the exact declared bounds.
Both slider types share the fraction conversion and step logic. The new boundary fixture precedes the production repair.
It covers decimal endpoints, interior steps, negative bounds, collapsed Float bounds, and cancellation near a large bound.

The root device run also found two fixture defects. The smoke fixture now preserves its state transitions before injected failures.
This permits dialog and second-page actions to become available. The rendered fixture now uses the database that ShellViewModel opens.
Cleanup uses the same database name. All existing workflow assertions remain.

The focused compile command passed again. The saved check record retains both compile runs and repair source hashes.
The root retains the failed device evidence. This package performed no repair device run and makes no workflow-pass claim.
