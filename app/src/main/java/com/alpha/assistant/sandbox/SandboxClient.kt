package com.alpha.assistant.sandbox

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class SandboxClient(context: Context) {
    private val context = context.applicationContext

    suspend fun execute(source: String, input: String): SandboxResult = mutex.withLock {
        try { SandboxContract.validateRequest(source, input) }
        catch (_: Exception) { return@withLock SandboxResult("INVALID_REQUEST") }
        withContext(Dispatchers.Main.immediate) {
            var connection: ServiceConnection? = null
            var bound = false
            try {
                withTimeoutOrNull(4000) {
                    suspendCancellableCoroutine { continuation ->
                        fun complete(result: SandboxResult) {
                            if (continuation.isActive) continuation.resume(result)
                        }
                        val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
                            val data = message.data
                            val status = data.getString("status") ?: "INVALID_RESULT"
                            val json = data.getString("json")
                            if (status == "OK") {
                                try { SandboxContract.validateResult(requireNotNull(json)) }
                                catch (_: Exception) { complete(SandboxResult("INVALID_RESULT")); return@Handler true }
                            }
                            complete(SandboxResult(status, json, data.getInt("uid"), data.getInt("pid"), data.getBoolean("networkDenied"), data.getBoolean("privateDataDenied")))
                            true
                        })
                        connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                try {
                                    Messenger(binder).send(Message.obtain(null, SandboxContract.EXECUTE).apply {
                                        replyTo = callback
                                        data = Bundle().apply { putString("source", source); putString("input", input) }
                                    })
                                } catch (_: RemoteException) { complete(SandboxResult("PROCESS_DIED")) }
                            }
                            override fun onServiceDisconnected(name: ComponentName) { complete(SandboxResult("PROCESS_DIED")) }
                            override fun onBindingDied(name: ComponentName) { complete(SandboxResult("PROCESS_DIED")) }
                            override fun onNullBinding(name: ComponentName) { complete(SandboxResult("UNAVAILABLE")) }
                        }
                        bound = context.bindService(Intent(context, JavaScriptSandboxService::class.java), connection!!, Context.BIND_AUTO_CREATE)
                        if (!bound) complete(SandboxResult("UNAVAILABLE"))
                    }
                } ?: SandboxResult("TIMEOUT")
            } finally {
                if (bound) context.unbindService(connection!!)
            }
        }
    }

    private companion object { val mutex = Mutex() }
}
