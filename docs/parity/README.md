# Reference parity contract

The protocol-3 implementation has a separate [current Compose coverage record](compose-coverage.md).
The [acceptance harness](compose-acceptance.md) preserves the six exact user requests and separates real and synthetic evidence.
The status statements below describe the recorded historical baseline.

This inventory describes the pinned reference commit `7ee06af1941729b00a110493ad9d1d9b5b127d17`.
It records source behavior, not device certification.
The Alphy baseline describes the existing working tree above commit `cbf25f0ba107b9b028580a5a83235b6a5537427d`.
Source hashes identify that baseline because the working tree contains uncommitted changes.

## Integrity criteria

The extractor must detect these failures:

- A missing pinned Git object or an unexpected catalog count.
- A missing declaration, repeated output key, or unrecorded duplicate registry row.
- A contract without source path, symbol, commit, and source hash.
- A mapping that claims full support without explicit evidence.
- A generated inventory that differs from the checked-in artifact.
- A candidate or summary that appears as a verified callable intent.

The extractor reads committed objects with `git show`.
It does not read the reference working tree, environment files, credentials, or device state.
All checks use Python standard-library modules.

## Files and reproduction

- `extract.py` reads the pinned Git objects and produces `inventory.json`.
- `alphy-baseline.json` contains reviewed status mappings, evidence hashes, and functional acceptance criteria.
- `inventory.json` contains finite catalogs, contracts, duplicate policies, and source provenance.
- `android-dispositions.json` assigns all registered SDK, calendar, and curated intent targets to explicit Android contracts or open evidence items.
- `verification.json` records the focused integrity check and artifact hashes.

Run these commands from the Alpha repository root:

```sh
python3 docs/parity/extract.py /Users/egavrin/Documents/deal-studio-ohos
python3 docs/parity/extract.py /Users/egavrin/Documents/deal-studio-ohos --check
```

The first command replaces only `docs/parity/inventory.json`.
The second command makes no file changes.
A clone with the pinned objects can replace the reference path.
Reference working-tree changes do not affect extraction.
The inventory contains no absolute reference path.

Status mappings describe the recorded Alphy baseline, not later changes.
After implementation, review the mappings and source hashes before you regenerate the inventory.
Automatic extraction never promotes a capability to `supported`.

## Inventory coverage

| Collection | Source rows | Unique keys | Meaning |
| --- | ---: | ---: | --- |
| UI components | 62 | 62 | `VeraUiCatalog.CATALOG` |
| UI helper exports | 7 | 7 | `VeraCompiler.buildStdUiModule` |
| Graphics operations | 14 | 14 | `VeraGfx.GFX_FUNCTIONS` |
| SDK adapters | 530 | 528 | `VeraSdkBackend.ADAPTERS`, last row wins |
| Calendar targets | 20 | 20 | `VeraCalendarSdk.calendarSdkTargets` |
| Live-data target | 1 | 1 | `VeraLive.LIVE_TARGET` |
| Curated intent addresses | 7 | 6 | `VeraIntentCatalog.INTENT_CATALOG`, variants retained |
| Offline Celia candidates | 682 | 682 | `rawfile/celia-intent-index.json` |
| Offline intent summaries | 640 | 640 | `rawfile/intent-summaries.json` |
| Dynamic intent registry | variable | 1 contract | `VeraIntentRegistry.loadRegistry` |
| Generation and repair workflows | 15 | 15 | Functions listed in `alphy-baseline.json` |

The SDK search index contains 11,684 declarations.
Only 548 indexed targets have registered adapters: 528 SDK targets and 20 calendar targets.
The remaining 11,136 indexed targets return the unimplemented stub through `runSdkAdapter`.
`Vera.Live.fetch` uses a separate dispatch branch and does not occur in that index.
The inventory does not duplicate all 11,136 stub contracts.
Its source hash and extraction count cover that boundary.
`isValidSdkTarget` also accepts targets before the index loads.
That permissive branch does not create a working adapter.

All source paths above use `entry/src/main/ets/vera/`, except `rawfile/`, which uses `entry/src/main/resources/rawfile/`.
Each inventory source includes the pinned commit, path, symbol, and source lines or JSON index.
The `sources` table adds the Git blob identifier and SHA-256.
The full source remains available from the pinned object.

## Duplicate and discovery behavior

`CoreFileKit.storageStatistics.getFreeSizeSync` occurs at adapter rows 29 and 281.
`CoreFileKit.storageStatistics.getTotalSizeSync` occurs at rows 30 and 287.
The `Map` constructor retains rows 281 and 287.
The inventory uses those effective bindings and records both occurrences.

`startNavigate` and `navigateTo` share this address:

```text
com.huawei.hmos.maps.app/entry/EntryAbility/StartNavigate
```

Their parameter contracts differ.
`VeraIntentIndex.allEntries` retains both discovery variants.
The inventory therefore has one runtime address with two complete source contracts.
It does not apply the SDK duplicate policy to these variants.

`searchOnMap` has tier `ignored`.
`ignoredIntent` excludes its bundle and intent name from discovery.
The reference reports that Maps ignores the query even when its result code is zero.
The reference also reports that `navigateTo` ignores the supplied starting location on its tested Huawei Maps app.
These source reports are limitations, not new device verification.
`unreachableTarget` refuses the reference clock bundle.

Celia candidates contain a bundle and intent name, but no module or ability.
`celiaEntryIfInstalled` supplies those fields only from the live device registry.
The registry lookup uses `bundle/IntentName` and retains the last matching registry row.
The raw-registry tier remains available for keys without a matched Celia candidate.
Curated entries can coexist with these tiers.
A score tie favors curated entries, then Celia entries, then raw entries.

The 640 summaries contain descriptions only.
They are not a frozen list of installed addresses.
Without a device snapshot, this package cannot enumerate the live registry or prove an intent's effect.
The dynamic-registry record states that limit explicitly.

## Status rules and current Alphy support

`alphy.status` has three values:

- `supported`: the stated functional scope exists in the recorded Alphy source.
- `unimplemented`: the complete reference contract is absent, including contracts with partial native equivalents.
- `android_platform_limitation`: the literal OHOS route or API is unavailable through Alphy's Android host.

The last status does not mean that Android lacks every functional equivalent.
Each equivalent still needs an Android contract, permission analysis, and functional evidence.
The inventory does not infer compatibility from similar names.
`reference_status` separately records registration, system-API restrictions, discovery exclusion, and metadata-only entries.
The SDK source metadata includes permission names, `systemapi`, API versions, deprecation, and capability guards.
There are 190 registered SDK keys whose index metadata sets `systemapi` to true.
Registration alone proves neither permission grants nor successful device execution.

Current Alphy has seven native controls: `column`, `row`, `text`, `button`, `input`, `toggle`, and `list`.
`ToolSpec.validateUi` defines their permitted properties.
`ToolUi.NativeNode` renders both `column` and `row` vertically.
Buttons send null values.
Inputs submit bounded strings explicitly.
Lists display typed records and can send a stable record ID.
Reference styles, numeric button payloads, enabled properties, and composite contracts remain absent.
Thus, these native subsets do not establish complete parity for any reference component.

`CapabilityCatalog` exposes these bounded Android actions:

| Alphy action | Current functional scope | Material limit |
| --- | --- | --- |
| `notification.post` | Post an Alphy notification | Active status does not prove display or reading |
| `calendar.read` | Read selected-calendar instances | At most 31 days and 50 events |
| `calendar.create` | Open an event creation form | Save result and event ID remain unverified |
| `link.open` | Open a public HTTPS URL | Validated public host only |
| `app.open` | Open settings, browser, calendar, or camera | Launch result does not prove a further action |
| `maps.open` | Open a geo search | Visible search results remain unverified |
| `vibration.play` | Request one vibration | 1..2000 milliseconds; physical result unverified |
| `flashlight.set` | Request boolean torch state | One available camera; physical state unverified |
| `photo.pick` | Open the system image picker | Selected content only |
| `file.pick` | Open the system document picker | Selected content and validated MIME only |
| `camera.capture` | Open the system camera | Thumbnail result requires native review |

The exact argument contracts remain in `alphy-baseline.json` and `CapabilityContract.kt`.
Direct calendar writes, arbitrary app control, and a generic `sdk.call` bridge remain unavailable.
Android assistant context is separate from generated tool access.
A generated tool cannot capture screens.

Alphy already validates a generated tool, executes a sandbox preview, and retries invalid generated output once with structured feedback.
It preserves drafts and accepted artifacts through separate transitions.
It supports state-preserving refinements, stale-command rejection, and cancellation checks before persistence.
The reference has additional needs selection, section assembly, composition edits, graphics generation, and handler smoke checks.
Its repair applies source edits for at most two rounds.
The inventory records those additional workflows as unimplemented.
The `generation.cancel` record marks only the shared cancellation behavior as supported.
This status rests on source evidence; this package does not repeat application tests.

## UI groups and functional acceptance

The inventory groups UI components by their role:

- Primitives: Card, Column, Divider, Icon, Image, Row, Scroll, Spacer, Text.
- Inputs: BackHandler, Button, Cell, Checkbox, DateField, IconButton, NumberField, Select, Slider, Tabs, TextField, TimeField, Toggle.
- Graphics and motion: Canvas, Motion, Path, Sparkline, Stage, Surface, Ticker, Transform.
- Composites: the remaining 32 catalog components, each listed in the inventory.

The seven helper exports are `When`, `numberToString`, `formatNumber`, `clockToString`, `dateToString`, `durationToString`, and `booleanToString`.
The catalog also defines 59 style sets and 13 child rules.
Both are part of the extracted contract.
SDK groups use their exact kit names.
Workflow groups separate generation, discovery, validation, repair, and refinement.

`verification_criteria` defines functional checks for each collection.
Each workflow also has a specific observable criterion.
An implementation must verify complete workflows with fixed inputs and saved outputs.
Device adapters additionally require permission denial, unavailable hardware, cancellation, and actual result checks where observable.
A platform acceptance code must not become a claim that the requested physical or external action completed.

## Safe current artifact format

**Requirement:** Alpha project instructions prohibit silent deletion of existing user data or credentials.
They also prohibit compatibility layers and migrations without an explicit request.

The current `ToolSpec.parse` accepts integer `formatVersion: 2` only.
The artifact has exactly eight top-level fields:

```json
{
  "formatVersion": 2,
  "title": "Counter",
  "description": "A local counter",
  "capabilities": [],
  "stateSchema": {"count": "number"},
  "initialState": {"count": 0},
  "ui": {"type": "column", "children": [
    {"type": "text", "text": "Count", "bind": "count"},
    {"type": "button", "text": "Add", "action": "add"}
  ]},
  "source": "const s = {...input.state}; if (input.event.type === 'action' && input.event.actionId === 'add') s.count += 1; return {state:s};"
}
```

The artifact is JSON data plus a JavaScript function body.
It is not VERA source, VBC2 bytecode, a WebView document, or native application code.
The host executes its source in the isolated sandbox.
Generated code cannot access host files, credentials, imports, network services, or native APIs directly.

The specification permits 1..40 state fields.
Scalar types are `number`, `string`, and `boolean`.
Numbers must be finite with absolute value at most `1e12`.
Strings have a 2,048-character limit.
State has a 48 KiB limit.
A list schema defines `type`, `maxItems`, and `fields`.
It permits at most 100 records and 12 scalar fields per record.
Every record has a nonempty, unique string `id` of at most 128 characters.
Nested records and arrays remain unsupported.

The specification has a 64 KiB JSON limit and depth limit 24.
Source has a 32 KiB limit.
UI has at most 80 nodes, maximum depth 8, and 1..30 children per container.
Each leaf has bounded text.
Action identifiers are unique and validated against the current layout.
Preview and render events preserve state and request no effects.
Action events can request at most three declared native effects.
The broker checks grants, Android permissions, and native confirmation.

Refinement can add fields with defaults.
It must preserve existing fields, types, collection definitions, IDs, and values.
This rule is stricter than the reference `carryState`, which can reset incompatible values or drop removed fields.
A parity implementation must preserve Alphy's data rule rather than copy that destructive reference behavior.

`docs/tool-integration.md` still describes version 1 and older limits.
This P0 package preserves that existing file and records the current source contract here.

## Known verification limits

This package checks catalog extraction, finite coverage, source provenance, duplicate policies, and byte-for-byte reproduction.
It does not certify device behavior, live registry contents, permissions, or physical effects.
It makes no model calls.
No emulator or device is available in this package.
The coordinating root owns implementation review and final acceptance.


## Target-specific Android dispositions

The acceptance repair replaces the blanket SDK applicability field.
All 554 registered SDK, calendar, and curated intent runtime keys now have a target-specific record.
Each record includes a P6 family assignment, implementation status, exact reference signature, source provenance, and next verification action.

| Disposition | Targets | Interpretation |
| --- | ---: | --- |
| `equivalent_candidate` | 56 | A named public API covers the stated function; the exact Alphy adapter remains unimplemented |
| `subset_candidate` | 128 | A named public API covers only the explicit boundary |
| `platform_restriction` | 5 | A documented authority or target-SDK restriction blocks the exact current-app operation |
| `unresolved` | 365 | Exact reference semantics or Android authority/result conversion still needs evidence |

Candidate status does not certify functional parity.
For example, `CameraManager.setTorchMode` accepts a boolean, while the reference accepts additional torch modes.
The entry retains that boundary and the existing `flashlight.set` subset.
The corresponding public declaration appears in the saved SDK evidence. [CameraManager reference](https://developer.android.com/reference/android/hardware/camera2/CameraManager#setTorchMode(java.lang.String,boolean))

The five restriction records concern two Wi-Fi state changes and three persistent hardware-identifier queries.
Android limits Wi-Fi state mutation for current target SDKs, with documented management and system-app exceptions. [WifiManager reference](https://developer.android.com/reference/android/net/wifi/WifiManager#setWifiEnabled(boolean))
Persistent identifier access also requires specific privilege, carrier authority, or a documented role. [TelephonyManager reference](https://developer.android.com/reference/android/telephony/TelephonyManager#getImei())
These records preserve the authority requirement and prohibit substitution with an unrelated identifier.
They do not treat an OHOS address as proof of Android impossibility.

The 365 unresolved records are explicit open contracts, not a generic unsupported label.
Each retains the exact input signature, result type, reference description, permission metadata, and pinned binding source.
Each also names the missing Android evidence and a family-specific next check.
Examples include OHOS distributed-session identities, DLP policy schemas, motor-control protocols, global input settings, and provider-specific music recommendations.
A MIME comparison cannot supply an OHOS uniform-type identifier.
Likewise, current P2P group removal cannot establish deletion of a saved persistent group.
Those mismatches remain unresolved rather than false subset claims.

The table below assigns every target to a finite P6 family.
An implementation package must resolve its open contracts or report each unavailable operation with evidence.
It must not silently omit unresolved targets.

| P6 family | Equivalent candidates | Subset candidates | Restrictions | Unresolved |
| --- | ---: | ---: | ---: | ---: |
| AVSessionKit | 0 | 0 | 0 | 5 |
| AbilityKit | 2 | 13 | 0 | 34 |
| AccessibilityKit | 0 | 0 | 0 | 13 |
| AdsKit | 0 | 0 | 0 | 1 |
| ArkData | 0 | 0 | 0 | 4 |
| ArkTS | 0 | 5 | 0 | 10 |
| ArkUI | 0 | 4 | 0 | 24 |
| AudioKit | 14 | 3 | 0 | 2 |
| BackgroundTasksKit | 0 | 2 | 0 | 8 |
| BasicServicesKit | 2 | 10 | 0 | 34 |
| CalendarKit | 13 | 7 | 0 | 0 |
| CameraKit | 0 | 1 | 0 | 0 |
| ConnectivityKit | 2 | 16 | 2 | 31 |
| CoreFileKit | 4 | 7 | 0 | 13 |
| DataProtectionKit | 0 | 0 | 0 | 8 |
| DeviceCertificateKit | 0 | 0 | 0 | 1 |
| DistributedServiceKit | 0 | 0 | 0 | 8 |
| DrmKit | 1 | 0 | 0 | 1 |
| FormKit | 0 | 1 | 0 | 6 |
| IMEKit | 0 | 0 | 0 | 1 |
| ImageKit | 0 | 0 | 0 | 2 |
| InputKit | 0 | 0 | 0 | 40 |
| LocalizationKit | 0 | 10 | 0 | 2 |
| LocationKit | 1 | 2 | 0 | 5 |
| MDMKit | 0 | 0 | 0 | 3 |
| MechanicKit | 0 | 0 | 0 | 4 |
| MediaKit | 5 | 0 | 0 | 2 |
| MultimodalAwarenessKit | 0 | 0 | 0 | 2 |
| NetworkKit | 6 | 6 | 0 | 8 |
| NotificationKit | 0 | 6 | 0 | 19 |
| PerformanceAnalysisKit | 4 | 9 | 0 | 36 |
| SensorServiceKit | 1 | 5 | 0 | 1 |
| TelephonyKit | 1 | 16 | 3 | 35 |
| UniversalKeystoreKit | 0 | 0 | 0 | 1 |
| curated_intents | 0 | 5 | 0 | 1 |

The saved Android evidence contains 152 public member declarations from the installed Android 37.0 SDK.
It also records member API history, SDK hashes, candidate family contracts, and official documentation links.
Signature evidence proves API presence, not permission grants, hardware behavior, or semantic equivalence.
The build baseline has minimum SDK 29 and target SDK 37.
Members above the minimum need an API guard or an explicitly unavailable result on older devices.

To reproduce the local signature check, use the recorded SDK installation:

```sh
python3 docs/parity/extract.py /Users/egavrin/Documents/deal-studio-ohos --check --android-sdk /opt/homebrew/share/android-commandlinetools
```

This optional check verifies SDK hashes, all saved public signatures, and their API-version records.
The ordinary `--check` command still requires only Python, Git, and the pinned reference objects.
It checks complete target coverage, concrete unresolved questions, family references, API evidence references, and restriction evidence.
