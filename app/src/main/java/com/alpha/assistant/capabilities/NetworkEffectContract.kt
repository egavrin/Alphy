package com.alpha.assistant.capabilities

import com.alpha.assistant.network.NetworkAccessDenial
import com.alpha.assistant.network.NetworkHeader
import com.alpha.assistant.network.NetworkLimits
import com.alpha.assistant.network.NetworkMethod
import com.alpha.assistant.network.NetworkOwner
import com.alpha.assistant.network.NetworkRequest
import com.alpha.assistant.network.NetworkResponse
import com.alpha.assistant.network.NetworkResult
import com.alpha.assistant.network.livesource.JsonLiveSource
import com.alpha.assistant.network.livesource.LiveSourceEffect
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object NetworkEffectContract {
    const val ID = "network.request"
    const val RECEIPT_ID = "network"
    // Inherited test limits. Measure capacity before releasing the complete network interface.
    val limits = NetworkLimits(1_024,1_024,2_048,4_000,3,2)
    val statuses = setOf("succeeded","denied","revoked","unavailable","failed","cancelled","unknownOutcome")
    private val methods = NetworkMethod.entries.map { it.name }
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val accessReasons = mapOf(
        NetworkAccessDenial.LOCAL_NETWORK_PERMISSION to "localNetworkPermission",
        NetworkAccessDenial.CLEARTEXT_POLICY to "cleartextPolicy",
        NetworkAccessDenial.POLICY_UNAVAILABLE to "policyUnavailable",
    )
    val definition = CapabilityDefinition(ID,"Сетевой запрос",CapabilityAction.NETWORK_REQUEST,emptyMap(),
        "Send one foreground HTTP or HTTPS request with the mini-app's network grant. Android local-network and cleartext policies also apply. Do not combine this request with another effect in the same action. For a JSON endpoint and field documented in a source excerpt or supplied in the user request, use GET without headers or body and supply extract with a kind and a path of keys or zero-based indexes. Do not guess an endpoint or field. Show the returned source and data time. Keep the last valid value and show that it is stale if a refresh fails.",
        "succeeded","An HTTP response was received. The status code can indicate server rejection. Only bounded UTF-8 response text is returned. An interrupted write can have an unknown outcome. Never retry it automatically.",
        foreground=true)

    fun argumentSchema() = closed(listOf("method","url"), JSONObject()
        .put("method",JSONObject().put("type","string").put("enum",JSONArray(methods)))
        .put("url",text(limits.maxMetadataBytes).put("format","absolute-http-url"))
        .put("headers",JSONObject().put("type","array").put("items",closed(listOf("name","value"),JSONObject()
            .put("name",text(limits.maxMetadataBytes)).put("value",text(limits.maxMetadataBytes)))))
        .put("bodyText",text(limits.maxRequestBytes)).put("extract",LiveSourceEffect.schema()))

    fun resultSchema() = JSONObject().put("oneOf",JSONArray(listOf(
        closed(listOf("httpStatus","bodyText"),JSONObject()
            .put("httpStatus",JSONObject().put("type","integer").put("minimum",100).put("maximum",599))
            .put("bodyText",text(limits.maxResponseBytes))),
        closed(listOf("source","dataTimeMs","value"), JSONObject().put("source",text(limits.maxMetadataBytes))
            .put("dataTimeMs",JSONObject().put("type","integer").put("minimum",0))
            .put("value",JSONObject().put("type",JSONArray(listOf("string","number","boolean"))))),
        closed(listOf("reason"),JSONObject().put("reason",JSONObject().put("type","string")
            .put("enum",JSONArray(accessReasons.values.toList())))),
        closed(emptyList(),JSONObject()),
    )))

    private fun closed(required: List<String>, properties: JSONObject) = JSONObject().put("type","object")
        .put("additionalProperties",false).put("required",JSONArray(required)).put("properties",properties)
    private fun text(bytes: Int) = JSONObject().put("type","string").put("maxLength",bytes)
    private fun keys(value: JSONObject) = value.keys().asSequence().toSet()
    private fun utf8(value: String) = value.encodeToByteArray(throwOnInvalidSequence=true)
    private fun string(value: JSONObject, key: String) = value.get(key) as? String ?: error("Invalid network request field")

    fun request(args: JSONObject): NetworkRequest {
        require(keys(args).containsAll(setOf("method","url")) && keys(args).all {it in setOf("method","url","headers","bodyText","extract")})
        val methodName=string(args,"method")
        val method = NetworkMethod.entries.firstOrNull {it.name==methodName} ?: error("Invalid network method")
        val url = string(args,"url")
        val parsed = try { URI(url) } catch (_: java.net.URISyntaxException) { error("Invalid network URL") }
        require(parsed.rawAuthority != null && '@' !in parsed.rawAuthority && parsed.rawFragment == null)
        val canonical=url.toHttpUrlOrNull()?.toString() ?: error("Invalid network URL")
        var metadata = utf8(canonical).size
        require(metadata <= limits.maxMetadataBytes)
        val headers = if (!args.has("headers")) emptyList() else (args.optJSONArray("headers") ?: error("Invalid network headers")).let { array ->
            (0 until array.length()).map { index ->
                val header = array.optJSONObject(index) ?: error("Invalid network header")
                require(keys(header) == setOf("name","value"))
                val name = string(header,"name")
                val value = string(header,"value")
                require(headerName.matches(name) && value.all {it in ' '..'~' || it == '\t'})
                metadata += name.length + value.length + 4
                require(metadata <= limits.maxMetadataBytes)
                NetworkHeader(name,value)
            }
        }
        val body = if (args.has("bodyText")) utf8(string(args,"bodyText")) else null
        require(body == null || !method.safe && body.size <= limits.maxRequestBytes)
        LiveSourceEffect.declaration(args, NetworkOwner("validation", "validation"), "source")
        return NetworkRequest(canonical,method,headers,body)
    }

    fun receipt(request: CapabilityRequest): CapabilityRequest {
        val method=string(request.args,"method")
        require(method in methods)
        return CapabilityRequest(RECEIPT_ID,ID,JSONObject().put("method",method))
    }

    fun readReceipt(json: JSONObject): CapabilityRequest {
        require(keys(json)==setOf("id","capability","args") && json.get("capability")==ID)
        val id=string(json,"id")
        val args=json.getJSONObject("args")
        require(id==RECEIPT_ID && keys(args)==setOf("method") && string(args,"method") in methods)
        return CapabilityRequest(id,ID,JSONObject(args.toString()))
    }

    fun receiptResult(status: String) = when(status) {
        "succeeded" -> CapabilityResult(status,"Получен ответ сервера.")
        "denied" -> CapabilityResult(status,"Доступ к сети недоступен. Проверьте разрешения в разделе «Доступ и функции».")
        "revoked" -> CapabilityResult(status,"Сетевой доступ отозван или сеанс изменился.")
        "unavailable" -> CapabilityResult(status,"Сетевой запрос недоступен.")
        "cancelled" -> CapabilityResult(status,"Сетевой запрос отменён.")
        "unknownOutcome" -> CapabilityResult(status,"Результат отправки не подтверждён. Проверьте его перед повтором.")
        else -> CapabilityResult("failed","Не удалось подтвердить результат запроса. Проверьте его перед повтором.")
    }

    fun durableResult(result: CapabilityResult): CapabilityResult {
        if(result.status=="denied") when(result.data.opt("reason")) {
            "localNetworkPermission" -> return CapabilityResult("denied","Android не разрешил доступ к локальной сети. Откройте «Доступ и функции».")
            "cleartextPolicy" -> return CapabilityResult("denied","Android не разрешил незашифрованный HTTP для этого адреса.")
        }
        return receiptResult(result.status)
    }

    fun outcome(result: NetworkResult, response: NetworkResponse?, source: JsonLiveSource? = null): CapabilityResult = when(result) {
        is NetworkResult.Delivered -> try {
            check(response!=null && response.status==result.status)
            val data = if (source != null) checkNotNull(LiveSourceEffect.sample(source, response))
                else JSONObject().put("httpStatus",response.status).put("bodyText",response.body.decodeToString(throwOnInvalidSequence=true))
            receiptResult("succeeded").copy(data=data)
                .also {validateResult(it.status,it.data)}
        } catch (_: Exception) {receiptResult("failed")}
        is NetworkResult.AccessDenied -> receiptResult("denied").copy(data=JSONObject().put("reason",accessReasons.getValue(result.reason)))
        is NetworkResult.UnknownOutcome -> receiptResult("unknownOutcome")
        NetworkResult.Denied -> receiptResult("denied")
        NetworkResult.Revoked -> receiptResult("revoked")
        NetworkResult.Cancelled -> receiptResult("cancelled")
        NetworkResult.Unavailable -> receiptResult("unavailable")
        is NetworkResult.Failed -> receiptResult("failed")
    }

    fun validateResult(status: String, data: JSONObject) {
        require(status in statuses)
        when {
            status=="succeeded" -> {
                if (keys(data) == setOf("source", "dataTimeMs", "value")) {
                    require(utf8(string(data,"source")).size <= limits.maxMetadataBytes)
                    val time = data.get("dataTimeMs")
                    require((time is Long || time is Int) && data.getLong("dataTimeMs") >= 0L)
                    val value = data.get("value")
                    require(value is Boolean || value is Number && value.toDouble().isFinite() || value is String && utf8(value).size <= limits.maxResponseBytes)
                } else {
                    require(keys(data)==setOf("httpStatus","bodyText"))
                    require(data.get("httpStatus") is Int && data.getInt("httpStatus") in 100..599)
                    require(utf8(string(data,"bodyText")).size <= limits.maxResponseBytes)
                }
            }
            status=="denied" && data.length()!=0 -> require(keys(data)==setOf("reason") && string(data,"reason") in accessReasons.values)
            else -> require(data.length()==0)
        }
    }
}
