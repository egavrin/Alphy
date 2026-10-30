# JavaScript engine assessment

Implementation choice: this package uses one pinned QuickJS engine through `quickjs-kt` 1.0.14.
The APK includes its native libraries. It does not depend on the installed WebView provider.
The pinned binding exposes a heap limit, stack limit, evaluation timeout, and explicit interruption.
Its JNI source calls `JS_NewContext`, `JS_SetMemoryLimit`, and `JS_SetMaxStackSize`.
The service does not register host functions or native modules.

Sources: [QuickJS binding source](https://github.com/dokar3/quickjs-kt/tree/v1.0.14), [runtime API](https://github.com/dokar3/quickjs-kt/blob/v1.0.14/quickjs/src/commonMain/kotlin/com/dokar/quickjs/QuickJs.kt), [JNI implementation](https://github.com/dokar3/quickjs-kt/blob/v1.0.14/quickjs/native/jni/quickjs_jni.c).

AndroidX JavaScriptEngine also supplies an out-of-process sandbox without a WebView UI.
Its availability depends on the WebView implementation, including on API 26 or later.
Applications must call `JavaScriptSandbox.isSupported()` before use.
Heap limits and isolate termination require separate feature checks.
Without isolate termination support, isolate cleanup can wait for evaluations or full sandbox closure.
These requirements add a device-dependent support gate for the requested limits.

Source: [Android JavaScriptEngine documentation](https://developer.android.com/develop/ui/views/layout/webapps/jsengine).

The connected CPH2765 reports Android API 37 and Google WebView `156.0.8078.47`.
This package does not install a second engine to query AndroidX features.
AndroidX support and optional features on this phone remain unverified.
The connected workflow tests the selected QuickJS implementation directly.
Android 10 and other supported ABIs still require device acceptance before a broad support claim.
