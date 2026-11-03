package com.alpha.assistant.tools

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** One parent * child matrix and one camera for all leaves of a native stage. */
internal object NativeLayerMath {
    fun identity()=doubleArrayOf(1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0, 0.0,0.0,0.0,1.0)
    fun multiply(a: DoubleArray,b: DoubleArray)=DoubleArray(16) {index ->
        val row=index/4; val column=index%4
        (0..3).sumOf {a[row*4+it]*b[it*4+column]}
    }
    fun pose(p: DoubleArray): DoubleArray {
        val x=p[0]*PI/180; val y=p[1]*PI/180; val z=p[2]*PI/180
        val rx=doubleArrayOf(1.0,0.0,0.0,0.0, 0.0,cos(x),-sin(x),0.0, 0.0,sin(x),cos(x),0.0, 0.0,0.0,0.0,1.0)
        val ry=doubleArrayOf(cos(y),0.0,sin(y),0.0, 0.0,1.0,0.0,0.0, -sin(y),0.0,cos(y),0.0, 0.0,0.0,0.0,1.0)
        val rz=doubleArrayOf(cos(z),-sin(z),0.0,0.0, sin(z),cos(z),0.0,0.0, 0.0,0.0,1.0,0.0, 0.0,0.0,0.0,1.0)
        val scale=identity().apply {this[0]=p[6];this[5]=p[6];this[10]=p[6]}
        val move=identity().apply {this[3]=p[3];this[7]=p[4];this[11]=p[5]}
        return multiply(move,multiply(rz,multiply(ry,multiply(rx,scale))))
    }
    fun project(m: DoubleArray,perspective: Double,x: Double,y: Double): DoubleArray {
        val px=m[0]*x+m[1]*y+m[3]; val py=m[4]*x+m[5]*y+m[7]; val z=m[8]*x+m[9]*y+m[11]
        val w=(1-z/perspective).coerceAtLeast(0.05)
        return doubleArrayOf(px/w,py/w,z)
    }
    fun target(node: JSONObject,state: JSONObject): DoubleArray {
        val values=if(node.has("pose")) node.getJSONArray("pose") else ToolUiCatalog.value(state,node.getString("poseBind")) as JSONArray
        return DoubleArray(8) {values.getDouble(it)}
    }
}

@Composable private fun animatedPose(node: JSONObject,state: JSONObject): DoubleArray {
    val target=NativeLayerMath.target(node,state)
    val duration=if(ValueAnimator.areAnimatorsEnabled()) node.optInt("durationMs",0) else 0
    return DoubleArray(8) {index ->
        animateFloatAsState(target[index].toFloat(),tween(duration,easing=FastOutSlowInEasing),label="Native pose $index").value.toDouble()
    }
}

@Composable internal fun NativeMotion(node: JSONObject,state: JSONObject,modifier: Modifier,content: @Composable () -> Unit) {
    val trigger=ToolUiCatalog.value(state,node.getString("trigger"))
    val progress=remember {Animatable(1f)}
    var initial by remember {mutableStateOf(true)}
    val preset=node.getString("preset")
    LaunchedEffect(trigger,preset) {
        val play=(!initial || node.optBoolean("animateInitial",false)) && ValueAnimator.areAnimatorsEnabled()
        initial=false
        if(play) {
            progress.snapTo(0f)
            progress.animateTo(1f,tween(when(preset) {"tumble" -> 900;"flip","shake" -> 450;"bounce" -> 650;"pop" -> 350;else -> 400}))
        } else progress.snapTo(1f)
    }
    Box(modifier.graphicsLayer {
        val p=progress.value; val e=1-(1-p).pow(3)
        when(preset) {
            "tumble" -> {rotationX=720*(1-e);rotationY=576*(1-e);rotationZ=144*(1-e);translationY=(-28*sin(PI*p)).toFloat()*density;scaleX=1+0.18f*sin(PI*p).toFloat();scaleY=scaleX}
            "flip" -> rotationY=90*(1-e)
            "shake" -> translationX=(14*sin(p*PI*6)*(1-p)).toFloat()*density
            "bounce" -> translationY=(-48*(1-p)*abs(cos(p*PI*2.5))).toFloat()*density
            "pop" -> {scaleX=1+0.35f*sin(PI*p).toFloat();scaleY=scaleX}
            "fade" -> {alpha=e;scaleX=0.96f+0.04f*e;scaleY=scaleX}
        }
    }) {content()}
}

@Composable internal fun NativeTransform(node: JSONObject,state: JSONObject,modifier: Modifier,content: @Composable () -> Unit) {
    val pose=animatedPose(node,state)
    val size=node.optInt("size",0)
    Box((if(size>0) modifier.size(size.dp) else modifier).graphicsLayer {
        rotationX=pose[0].toFloat();rotationY=pose[1].toFloat();rotationZ=pose[2].toFloat()
        translationX=pose[3].toFloat()*density;translationY=pose[4].toFloat()*density
        val depth=(900/(900-pose[5]).coerceAtLeast(45.0)).toFloat()
        scaleX=pose[6].toFloat()*depth;scaleY=scaleX;alpha=pose[7].toFloat();cameraDistance=900*density
        if(node.optBoolean("solid") && cos(pose[0]*PI/180)*cos(pose[1]*PI/180)<0) alpha=0f
    },contentAlignment=Alignment.Center) {content()}
}

private data class NativeLayer(val key: String,val content: List<JSONObject>,val matrix: DoubleArray,val opacity: Double,val size: Int,val solid: Boolean)

@Composable internal fun NativeStage(node: JSONObject,state: JSONObject,modifier: Modifier,render: @Composable (JSONObject) -> Unit) {
    val layers=mutableListOf<NativeLayer>()
    @Composable fun collect(transform: JSONObject,parent: DoubleArray,opacity: Double,path: String) {
        if(!ToolUiCatalog.visible(transform,state)) return
        val pose=animatedPose(transform,state)
        val matrix=NativeLayerMath.multiply(parent,NativeLayerMath.pose(pose))
        val children=transform.getJSONArray("children")
        val content=(0 until children.length()).map {children.getJSONObject(it)}.filter {it.getString("type") != "transform"}
        if(content.isNotEmpty()) layers.add(NativeLayer(path,content,matrix,opacity*pose[7],transform.optInt("size",0),transform.optBoolean("solid")))
        repeat(children.length()) {index -> val child=children.getJSONObject(index)
            if(child.getString("type") == "transform") key(index) {collect(child,matrix,opacity*pose[7],"$path.$index")}
        }
    }
    val children=node.getJSONArray("children")
    repeat(children.length()) {index -> key(index) {collect(children.getJSONObject(index),NativeLayerMath.identity(),1.0,"$index")}}
    val perspective=node.optDouble("perspective",900.0)
    val floor=node.optBoolean("floor")
    val stageModifier=modifier.height(node.optInt("height",260).dp)
    BoxWithConstraints(if(floor) stageModifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.shapes.medium) else stageModifier,contentAlignment=Alignment.Center) {
        for(layer in layers.sortedBy {it.matrix[11]}) key(layer.key) {
            if(layer.solid && layer.matrix[10] <= 0.0) continue
            val edge=if(layer.size>0) layer.size.dp else minOf(maxWidth,maxHeight)
            Box(Modifier.size(edge).drawWithContent {
                val w=size.width; val h=size.height
                val source=floatArrayOf(0f,0f,w,0f,w,h,0f,h)
                val destination=FloatArray(8)
                repeat(4) {i ->
                    val projected=NativeLayerMath.project(layer.matrix,perspective,(source[i*2]-w/2)/density.toDouble(),(source[i*2+1]-h/2)/density.toDouble())
                    destination[i*2]=(projected[0]*density+w/2).toFloat();destination[i*2+1]=(projected[1]*density+h/2).toFloat()
                }
                val matrix=android.graphics.Matrix()
                if(matrix.setPolyToPoly(source,0,destination,0,4)) {
                    val canvas=drawContext.canvas.nativeCanvas
                    val save=canvas.save()
                    canvas.concat(matrix)
                    val alpha=(layer.opacity*255).toInt().coerceIn(0,255)
                    val alphaSave=canvas.saveLayerAlpha(0f,0f,w,h,alpha)
                    drawContent()
                    if(layer.solid) drawRect(Color.Black.copy(alpha=(0.28*(1-abs(layer.matrix[10]).coerceAtMost(1.0))).toFloat()))
                    canvas.restoreToCount(alphaSave);canvas.restoreToCount(save)
                }
            },contentAlignment=Alignment.Center) {Column {layer.content.forEach {render(it)}}}
        }
    }
}

@Composable internal fun NativeTransition(node:JSONObject,state:JSONObject,modifier:Modifier,content:@Composable () -> Unit) {
    val visible=ToolUiCatalog.value(state,node.getString("bind")) as Boolean
    val duration=if(ValueAnimator.areAnimatorsEnabled())node.optInt("durationMs",250) else 0
    val enter=androidx.compose.animation.fadeIn(tween(duration)) + when(node.getString("preset")) {
        "fadeSlide" -> androidx.compose.animation.slideInVertically(tween(duration)) {it/4}
        "expand" -> androidx.compose.animation.expandVertically(tween(duration))
        else -> androidx.compose.animation.EnterTransition.None
    }
    val exit=androidx.compose.animation.fadeOut(tween(duration)) + when(node.getString("preset")) {
        "fadeSlide" -> androidx.compose.animation.slideOutVertically(tween(duration)) {it/4}
        "expand" -> androidx.compose.animation.shrinkVertically(tween(duration))
        else -> androidx.compose.animation.ExitTransition.None
    }
    androidx.compose.animation.AnimatedVisibility(visible,modifier,enter=enter,exit=exit) {Column {content()}}
}
