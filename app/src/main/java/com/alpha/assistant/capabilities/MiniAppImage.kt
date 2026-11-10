package com.alpha.assistant.capabilities

import com.alpha.assistant.images.ImageAspect
import com.alpha.assistant.images.ImageIntent

internal object MiniAppImage {
    const val ID = "image.generate"
    const val RESOURCE = "generatedImage"

    fun intent(effect: CapabilityEffect): ImageIntent = effect.request.args.let {
        require(effect.request.capability == ID)
        ImageIntent("runtime", it.getString("prompt"), it.getString("alt"), ImageAspect.read(it.getString("aspect")))
    }
}
