package com.alpha.assistant.tools

import android.net.LocalServerSocket
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.platform.app.InstrumentationRegistry
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.AiProvider
import com.alpha.assistant.cloud.CloudFailure
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.DataInputStream
import java.io.File

/** Test-only channel. ADB authenticates the host; SO_PEERCRED rejects other Android application UIDs. */
internal object OneUseCredential {
    fun receive(runId: String,timeoutMs: Int=30000,onReady: () -> Unit): AiAccount {
        require(runId.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val server=LocalServerSocket("compose-credential-$runId")
        try {
            onReady()
            val poll=StructPollfd().apply {fd=server.fileDescriptor;events=OsConstants.POLLIN.toShort()}
            check(Os.poll(arrayOf(poll),timeoutMs)>0) {"Credential peer timed out."}
            server.accept().use {socket ->
                server.close()
                check(socket.peerCredentials.uid in setOf(0,2000)) {"Credential peer is not the ADB transport."}
                socket.soTimeout=5000
                val input=DataInputStream(socket.inputStream)
                val length=input.readInt()
                check(length in 1..8192) {"Invalid credential envelope."}
                val bytes=ByteArray(length)
                try {
                    input.readFully(bytes)
                    val account=account(bytes)
                    socket.outputStream.write(1)
                    socket.outputStream.flush()
                    return account
                } finally {bytes.fill(0)}
            }
        } finally {server.close()}
    }

    /** `<provider id>\n<key>`. The texts are constant, because a malformed envelope can hold the key in any part. */
    fun account(bytes: ByteArray): AiAccount {
        val split=bytes.indexOf('\n'.code.toByte())
        check(split>0 && split<bytes.lastIndex && bytes.count {it=='\n'.code.toByte()}==1) {"Invalid credential envelope."}
        val id=String(bytes,0,split,Charsets.US_ASCII)
        val provider=checkNotNull(AiProvider.entries.firstOrNull {it.id==id}) {"Unknown credential provider."}
        check((split+1..bytes.lastIndex).all {bytes[it].toInt() in 33..126}) {"Invalid credential payload."}
        return AiAccount(provider,String(bytes,split+1,bytes.size-split-1,Charsets.US_ASCII))
    }
}

/** The chat model of a live run: the host's `aiModel` argument, else the first curated model. It fails before any provider call. */
internal fun AiAccount.liveChatModel(requested: String?=InstrumentationRegistry.getArguments().getString("aiModel")): String {
    val models=provider.profile.chatModels
    if(requested==null) return models.first()
    require(requested in models) {"aiModel $requested is not a curated ${provider.id} chat model."}
    return requested
}

/**
 * The frame of one paid check that `run.py live-check` starts. A suite run skips it. The account comes once through
 * [OneUseCredential.receive]. The report at `live-check/<run id>/report.json` holds the test, the phase, the provider, the model,
 * and the result or the failure, never the key. When [missing] gives a reason, the provider lacks the function: the check
 * records `skipped` and skips before any provider call. [run] gets the account and the [model] that it uses, and returns the result.
 */
internal fun liveCheck(test: String, missing: (AiAccount) -> String?, model: (AiAccount) -> String?, run: (AiAccount, String?) -> String) {
    val runId = InstrumentationRegistry.getArguments().getString("liveRunId")
    assumeTrue("Only run.py live-check starts this paid check.", runId != null)
    require(runId!!.matches(Regex("live-[a-f0-9]{12}")))
    val directory = File(checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "live-check/$runId")
    check(directory.mkdirs())
    val report = JSONObject().put("test", test)
    fun save(phase: String) = File(directory, "report.tmp").apply {
        writeText(report.put("phase", phase).toString(2))
        check(renameTo(File(directory, "report.json")))
    }
    val account = OneUseCredential.receive(runId) { save("credentialReady") }
    val reason = missing(account)
    report.put("provider", account.provider.id).put("model", JSONObject.NULL)
    if (reason != null) save("skipped")
    assumeTrue(reason, reason == null)
    try {
        val chosen = model(account)
        report.put("model", chosen ?: JSONObject.NULL)
        save("running")
        report.put("result", run(account, chosen))
        save("complete")
    } catch (failure: Throwable) {
        report.put("failure", (failure as? CloudFailure)?.code ?: failure.javaClass.simpleName)
        // The assertion texts of the checks name what the provider gave, never the key.
        if (failure is AssertionError) report.put("detail", failure.message)
        save("failed")
        throw failure
    }
}
