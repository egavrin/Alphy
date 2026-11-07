package com.offlineassistant.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

internal fun LongArray.tensor(environment: OrtEnvironment, shape: LongArray, owner: MutableList<OnnxTensor>): OnnxTensor =
    OnnxTensor.createTensor(environment, LongBuffer.wrap(this), shape).also(owner::add)

internal fun FloatArray.tensor(environment: OrtEnvironment, shape: LongArray, owner: MutableList<OnnxTensor>): OnnxTensor =
    OnnxTensor.createTensor(environment, FloatBuffer.wrap(this), shape).also(owner::add)

internal fun OrtSession.Result.floatOutput(name: String): FloatArray {
    val tensor = get(name).orElseThrow { IllegalStateException("Missing Silero output: $name") } as OnnxTensor
    val buffer = tensor.floatBuffer
    return FloatArray(buffer.remaining()).also(buffer::get)
}
