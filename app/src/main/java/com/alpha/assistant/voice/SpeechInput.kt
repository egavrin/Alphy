package com.alpha.assistant.voice

import android.content.Context
import com.alpha.assistant.cloud.AiAccount
import com.alpha.assistant.cloud.Endpoints

/**
 * One way to turn speech into text (spec 12A). [capture] returns the transcript only after the speech ends, or null when
 * it heard no speech. It throws [com.alpha.assistant.cloud.CloudFailure] or [SpeechUnavailable].
 */
internal interface SpeechInput {
    suspend fun capture(oneShot: Boolean, onLevel: (Float) -> Unit, onRecognizing: () -> Unit): String?
    /** «Завершить запись»: the speech so far becomes the utterance. */
    fun finish()
    fun cancel()
}

/** The phone cannot recognize speech now. [message] is the status. [download] starts the language download, or is null. */
internal class SpeechUnavailable(message: String, val download: (() -> Unit)? = null) : Exception(message)

/** The provider of the account recognizes the speech, or the phone does when the provider has no transcription request. */
internal fun speechInputFor(account: AiAccount, context: Context, endpoints: Endpoints): SpeechInput =
    if (account.provider.profile.transcription != null) CloudSpeechInput(account, endpoints, PcmCapture(Microphone))
    else PhoneSpeechInput(AndroidPhoneRecognizer(context), context)
