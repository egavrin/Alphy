# Foundation verification

The following failure cases define the focused instrumentation checks before implementation.

- A valid function must receive JSON input and return JSON state.
- An infinite loop must stop within the execution budget.
- A rejected invocation must not prevent the next invocation.
- A large allocation and recursion must fail within the heap and stack budgets.
- Invalid JSON, excessive nesting, oversized source, and oversized output must fail.
- A runtime error must produce an explicit failure.
- JavaScript must not expose Android, Java, filesystem, network, or process bindings.
- The service UID and PID must differ from the application UID and PID.
- The isolated process must fail to create an Internet socket and read the private sentinel.
- A result with effects must fail. This foundation permits pure preview only.

These tests require a device because host tests cannot prove Android process isolation.
The connected test log records inputs and outcomes. The APK checksum identifies the tested revision.

## Recorded result

The final connected workflow passed on CPH2765 with Android API 37.
The debug host had Internet permission. Its socket creation succeeded.
The isolated service denied socket creation and private sentinel access.
UID and PID assertions proved separation from the host process.
The infinite-loop invocation returned `INTERRUPTED` in 732 ms, including service creation.
Allocation, recursion, malformed data, unwanted effects, output limits, and recovery assertions passed.
The initial runner attempt failed because the Kotlin test returned a non-void value.
The explicit `Unit` return fixed that harness failure before the successful runs.

The evidence directory is `docs/evidence/foundation/`.
`revision.json` identifies the exact source snapshot and APK with SHA-256 hashes.
`invocations.log` contains expected and observed outcomes for each source input.
`test-result.xml`, `build.log`, and `device.log` record the successful commands.
`zipalign.log` proves APK ZIP alignment for 16 KiB pages.
`native-alignment.log` records ARM64 QuickJS ELF segments with 16 KiB alignment.
`launch.log` records successful installation and activity launch after Gradle removed its temporary test installation.

Android 10 runtime acceptance, other ABI execution, and forced watchdog process termination remain untested.
The test covers engine interruption. It does not force a native hang.
Speech, cloud chat, generated UI rendering, widgets, and automation are outside this package.
