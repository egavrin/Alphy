package com.alpha.assistant.tools

import android.net.LocalServerSocket
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.DataInputStream

/** Test-only channel. ADB authenticates the host; SO_PEERCRED rejects other Android application UIDs. */
internal object OneUseCredential {
    fun receive(runId: String,timeoutMs: Int=30000,onReady: () -> Unit): String {
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
                    check(bytes.all {it.toInt() in 33..126}) {"Invalid credential payload."}
                    val key=String(bytes,Charsets.US_ASCII)
                    socket.outputStream.write(1)
                    socket.outputStream.flush()
                    return key
                } finally {bytes.fill(0)}
            }
        } finally {server.close()}
    }
}
