package com.alpha.assistant.sandbox

import android.content.Context
import androidx.javascriptengine.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface RuntimeSandbox {
    fun isFeatureSupported(feature: String): Boolean
    fun createIsolate(parameters: IsolateStartupParameters): JavaScriptIsolate
    fun close()
}
private class AndroidXSandbox(private val native: JavaScriptSandbox): RuntimeSandbox {
    override fun isFeatureSupported(feature: String)=native.isFeatureSupported(feature)
    override fun createIsolate(parameters: IsolateStartupParameters)=native.createIsolate(parameters)
    override fun close()=native.close()
}
/** One application-owned AndroidX sandbox. Its two isolates share the platform's moderate isolation boundary. */
internal class AndroidJsRuntime internal constructor(context: Context,private val supported: ()->Boolean = JavaScriptSandbox::isSupported,
    private val connect: suspend(Context)->RuntimeSandbox = {AndroidXSandbox(JavaScriptSandbox.createConnectedInstanceAsync(it).awaitResult())}) {
    private val context=context.applicationContext
    private val lock=Any()
    private val watchdog=ScheduledThreadPoolExecutor(1) {task -> Thread(task,"javascript-deadline").apply {isDaemon=true}}.apply {removeOnCancelPolicy=true}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var generation=0L
    private data class Connection(val generation: Long,val sandbox: RuntimeSandbox)
    private var connecting: CompletableDeferred<Connection>?=null
    private var current: Connection?=null
    private var retiring: CompletableDeferred<Unit>?=null
    private class Slot {
        val released=CompletableDeferred<Unit>()
        var isolate: Isolate?=null
    }
    private val slots=mutableMapOf<Boolean,Slot>()

    suspend fun availability(): SandboxResult = try {
        if(withTimeoutOrNull(4000) {connection()} == null)SandboxResult("UNAVAILABLE") else SandboxResult("OK")
    }
        catch(cancel: CancellationException) {throw cancel}
        catch(failure: RuntimeFailure) {SandboxResult(failure.status)}

    private suspend fun connection(): Connection {
        while(true) {
            var start: CompletableDeferred<Connection>?=null
            var epoch=0L
            val (wait,ready)=synchronized(lock) {
                val retirement=retiring
                if(retirement!=null) retirement to null
                else {
                    val result=connecting ?: CompletableDeferred<Connection>().also {connecting=it;start=it;epoch=++generation}
                    null to result
                }
            }
            if(wait!=null) {wait.await();continue}
            start?.let {result -> scope.launch {
                var sandbox: RuntimeSandbox?=null
                try {
                    if(!supported())throw RuntimeFailure("UNAVAILABLE")
                    sandbox=connect(context)
                    if(!hasRequiredFeatures(sandbox::isFeatureSupported))throw RuntimeFailure("UNAVAILABLE")
                    val connection=Connection(epoch,sandbox)
                    synchronized(lock) {current=connection}
                    result.complete(connection)
                } catch(failure: Exception) {
                    try {sandbox?.close()} catch(_: Exception) { }
                    synchronized(lock) {if(connecting === result)connecting=null}
                    result.completeExceptionally(if(failure is RuntimeFailure)failure else RuntimeFailure("UNAVAILABLE"))
                }
            }}
            return checkNotNull(ready).await()
        }
    }

    private suspend fun reserve(connection: Connection,retained: Boolean,slot: Slot,waitForRelease: Boolean) {
        while(true) {
            currentCoroutineContext().ensureActive()
            val occupied=synchronized(lock) {
                if(current !== connection || retiring!=null)throw RuntimeFailure("SANDBOX_DIED")
                val previous=slots[retained]
                if(previous==null) slots[retained]=slot
                previous
            } ?: return
            if(!waitForRelease) throw RuntimeFailure("BUSY")
            occupied.released.await()
        }
    }

    private fun release(retained: Boolean,slot: Slot) {
        synchronized(lock) {if(slots[retained]===slot) slots.remove(retained)}
        slot.released.complete(Unit)
    }

    suspend fun acquire(retained: Boolean,maxReturnBytes: Int,waitForRetainedRelease: Boolean=false): Isolate {
        val connection=withTimeoutOrNull(4000) {connection()} ?: throw RuntimeFailure("UNAVAILABLE")
        val slot=Slot()
        var native: JavaScriptIsolate?=null
        var owned: Isolate?=null
        try {
            if(retained && waitForRetainedRelease) {
                if(withTimeoutOrNull(RETAINED_HANDOFF_MS) {reserve(connection,true,slot,waitForRelease=true)}==null)
                    throw RuntimeFailure("BUSY")
            } else reserve(connection,retained,slot,waitForRelease=false)
            currentCoroutineContext().ensureActive()
            native=connection.sandbox.createIsolate(IsolateStartupParameters().apply {
                setMaxHeapSizeBytes(SandboxContract.HEAP_BYTES)
                setMaxEvaluationReturnSizeBytes(maxReturnBytes)
            })
            val isolate=Isolate(connection.generation,native) {release(retained,slot)}
            owned=isolate
            synchronized(lock) {
                if(current !== connection || slots[retained] !== slot)throw RuntimeFailure("SANDBOX_DIED")
                slot.isolate=isolate
            }
            native.addOnTerminatedCallback(direct) {info ->
                if(info.status in setOf(TerminationInfo.STATUS_MEMORY_LIMIT_EXCEEDED,TerminationInfo.STATUS_SANDBOX_DEAD))invalidate(connection.generation)
                else isolate.close("SCRIPT_ERROR")
            }
            val started=android.os.SystemClock.elapsedRealtime()
            val ready=isolate.evaluate("'ready'",SandboxContract.EVALUATION_MS)
            android.util.Log.i("AndroidJsRuntime","readiness retained=$retained status=${ready.status} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
            if(ready.status!="OK") {isolate.close();throw RuntimeFailure(ready.status)}
            return isolate
        } catch(failure: Exception) {
            if(owned!=null) owned.close() else try {native?.close()} finally {release(retained,slot)}
            if(failure is CancellationException)throw failure
            if(failure is RuntimeFailure)throw failure
            invalidate(connection.generation)
            throw RuntimeFailure("SANDBOX_DIED")
        }
    }

    /** No action is retried. All users of a dead sandbox lose their lease before another sandbox can connect. */
    private fun invalidate(epoch: Long) {
        val retirement=CompletableDeferred<Unit>()
        val (sandbox,occupied)=synchronized(lock) {
            val active=current
            if(active==null || active.generation!=epoch || retiring!=null)return
            retiring=retirement;current=null;connecting=null
            val occupied=slots.values.toList();slots.clear()
            active.sandbox to occupied
        }
        try {
            occupied.forEach {it.isolate?.close("SANDBOX_DIED")}
            sandbox.close()
        } finally {
            synchronized(lock) {if(retiring === retirement)retiring=null}
            occupied.forEach {it.released.complete(Unit)}
            retirement.complete(Unit)
        }
    }

    inner class Isolate internal constructor(private val epoch: Long,private val native: JavaScriptIsolate,private val releaseSlot: ()->Unit) {
        private val stateLock=Any()
        private val busy=AtomicBoolean(false)
        private var sequence=0L
        private var terminal: String?=null
        val generation: Long get()=epoch
        val status: String? get()=synchronized(stateLock) {terminal}
        suspend fun evaluate(source: String,deadlineMs: Long): SandboxResult {
            status?.let {return SandboxResult(it)}
            if(!busy.compareAndSet(false,true))return SandboxResult("BUSY")
            val seq=synchronized(stateLock) {++sequence}
            try {
                val result=suspendCancellableCoroutine<String> {continuation ->
                    val startedAtNs=System.nanoTime()
                    val deadlineNs=TimeUnit.MILLISECONDS.toNanos(deadlineMs)
                    val completed=AtomicBoolean(false)
                    fun expire() {
                        close("TIMEOUT")
                        if(continuation.isActive)continuation.resumeWithException(RuntimeFailure(status ?: "TIMEOUT"))
                    }
                    val timeout=watchdog.schedule({
                        if(completed.compareAndSet(false,true))expire()
                    },deadlineMs,TimeUnit.MILLISECONDS)
                    continuation.invokeOnCancellation {completed.set(true);timeout.cancel(false);close("CLOSED")}
                    try {
                        if(!continuation.isActive)return@suspendCancellableCoroutine
                        val future=native.evaluateJavaScriptAsync(source)
                        future.addListener({
                            if(completed.compareAndSet(false,true)) {
                                timeout.cancel(false)
                                if(continuation.isActive) {
                                    val stale=synchronized(stateLock) {terminal!=null || sequence!=seq}
                                    if(stale)continuation.resumeWithException(RuntimeFailure(status ?: "CLOSED"))
                                    else {
                                        val output=runCatching {future.get()}
                                        // A delayed watchdog must not let a late callback publish its result.
                                        if(System.nanoTime()-startedAtNs>=deadlineNs)expire()
                                        else output.fold(continuation::resume) {continuation.resumeWithException(cause(it))}
                                    }
                                }
                            }
                        },direct)
                    } catch(failure: Exception) {
                        if(completed.compareAndSet(false,true)) {timeout.cancel(false);if(continuation.isActive)continuation.resumeWithException(failure)}
                    }
                }
                status?.let {return SandboxResult(it)}
                return SandboxResult("OK",result)
            } catch(cancel: CancellationException) {close("CLOSED");throw cancel}
              catch(failure: Exception) {
                val error=cause(failure)
                val code=when(error) {
                    is MemoryLimitExceededException -> {invalidate(epoch);"MEMORY_LIMIT"}
                    is SandboxDeadException -> {invalidate(epoch);"SANDBOX_DIED"}
                    is EvaluationResultSizeLimitExceededException -> "INVALID_RESULT"
                    is RuntimeFailure -> error.status
                    else -> status ?: "SCRIPT_ERROR"
                }
                close(code);return SandboxResult(code)
            } finally {busy.set(false)}
        }
        fun close(reason: String="CLOSED") {
            val changed=synchronized(stateLock) {
                if(terminal!=null)false else {terminal=reason;++sequence;true}
            }
            if(!changed)return
            try {native.close()} finally {releaseSlot()}
        }
    }

    companion object {
        private const val RETAINED_HANDOFF_MS=4_000L
        private val direct=Executor {it.run()}
        @Volatile private var instance: AndroidJsRuntime?=null
        fun get(context: Context): AndroidJsRuntime=instance ?: synchronized(this) {instance ?: AndroidJsRuntime(context).also {instance=it}}
        internal fun hasRequiredFeatures(supports: (String)->Boolean)=listOf(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
            JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT).all(supports)
        private fun cause(error: Throwable): Throwable=if(error is ExecutionException)error.cause ?: error else error
        private suspend fun <T> ListenableFuture<T>.awaitResult(): T=suspendCancellableCoroutine {continuation ->
            addListener({if(continuation.isActive)try {continuation.resume(get())}catch(failure: Exception) {continuation.resumeWithException(cause(failure))}},direct)
        }
    }
}
internal class RuntimeFailure(val status: String): IllegalStateException(status)
