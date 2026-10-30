package com.alpha.assistant.sandbox

import android.app.Service
import android.content.Intent
import android.os.*
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class JavaScriptSandboxService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val busy = AtomicBoolean(false)
    private val watchdogThread = HandlerThread("sandbox-watchdog").apply { start() }
    private val watchdog = Handler(watchdogThread.looper)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != SandboxContract.EXECUTE) return@Handler true
        val reply = message.replyTo ?: return@Handler true
        if (!busy.compareAndSet(false, true)) {
            respond(reply, "BUSY")
            return@Handler true
        }
        val source = message.data.getString("source").orEmpty()
        val input = message.data.getString("input").orEmpty()
        scope.launch {
            val terminate = Runnable { Process.killProcess(Process.myPid()) }
            watchdog.postDelayed(terminate, SandboxContract.PROCESS_MS)
            try {
                try { SandboxContract.validateRequest(source, input) }
                catch (_: Exception) { respond(reply, "INVALID_REQUEST"); return@launch }
                val runtime = QuickJs.create(Dispatchers.Default)
                try {
                    runtime.memoryLimit = SandboxContract.HEAP_BYTES
                    runtime.maxStackSize = SandboxContract.STACK_BYTES
                    runtime.evaluationTimeoutMillis = SandboxContract.EVALUATION_MS
                    val result = runtime.evaluate<String>(
                        "JSON.stringify((function(input){'use strict';\n$source\n})(JSON.parse(${JSONObject.quote(input)})))"
                    )
                    try { SandboxContract.validateResult(result) }
                    catch (_: Exception) { respond(reply, "INVALID_RESULT"); return@launch }
                    respond(reply, "OK", result)
                } finally { runtime.close() }
            } catch (_: QuickJsInterruptedException) {
                respond(reply, "INTERRUPTED")
            } catch (_: Exception) {
                respond(reply, "SCRIPT_ERROR")
            } finally {
                watchdog.removeCallbacks(terminate)
                busy.set(false)
            }
        }
        true
    })

    private fun respond(reply: Messenger, status: String, json: String? = null) {
        val networkDenied = try {
            val socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
            Os.close(socket)
            false
        } catch (error: ErrnoException) {
            error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM
        }
        val response = Message.obtain(null, SandboxContract.EXECUTE).apply {
            data = Bundle().apply {
                putString("status", status)
                putString("json", json)
                putInt("uid", Process.myUid())
                putInt("pid", Process.myPid())
                putBoolean("networkDenied", networkDenied)
                putBoolean("privateDataDenied", !File(applicationInfo.dataDir, "files/isolation-sentinel").canRead())
            }
        }
        try { reply.send(response) } catch (_: RemoteException) { }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onDestroy() {
        scope.cancel()
        watchdogThread.quitSafely()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
