package com.clawmate.platform.mika

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.vision.VisionService
import android.content.Context
import android.content.pm.ApplicationInfo
import java.net.URI
import okhttp3.OkHttpClient

/**
 * 看图模型配置（用户在「云模型」里填的 Decider 视觉模型）。
 *
 * 手机任务有它时，`phone_describe` / `phone_find_visually` 才可用；没有配置就保持隐藏，
 * 由界面提示先配置，而不是让主模型去猜屏上的东西。
 */
data class MikaVisionConfig(
    val baseUrl: String,
    val modelName: String,
    val key: String,
) {
    companion object {
        /** Only an opted-in build contains this resource. Production uses HTTPS. */
        fun workflowHttpOrigin(context: Context): String {
            if (context.packageName != "com.clawmate.experience" && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return ""
            val id = context.resources.getIdentifier("workflow_decider_http_origin", "string", context.packageName)
            return if (id == 0) "" else context.getString(id)
        }

        fun permitsWorkflowHttp(url: String, origin: String): Boolean {
            if (origin.isEmpty()) return false
            val endpoint = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            return endpoint.scheme == "http" && endpoint.host != null && endpoint.rawUserInfo == null &&
                "http://${endpoint.rawAuthority}" == origin.removeSuffix("/") &&
                endpoint.rawQuery == null && endpoint.rawFragment == null &&
                endpoint.rawPath.removeSuffix("/") in setOf("", "/v1", "/v1/chat/completions")
        }

        /** 地址必须能按 Mika 的规则规范化；模型名留空使用服务默认，最长 180 字；Key 可为空但不得超长。 */
        fun parse(url: String, model: String, key: String, workflowHttpOrigin: String = ""): MikaVisionConfig? {
            val name = model.trim()
            if (name.length > 180 || key.length > 4096) return null
            val base = MikaEndpoints.parse(url)?.second ?: if (permitsWorkflowHttp(url, workflowHttpOrigin)) {
                workflowHttpOrigin.removeSuffix("/") + "/v1"
            } else return null
            return MikaVisionConfig(base, name, key.trim())
        }
    }
}

/** 用看图模型配置造一个 Mika 的 [VisionService]；图片处理走本机的 [AndroidImageOps]。 */
internal fun mikaVisionService(config: MikaVisionConfig, http: OkHttpClient) =
    VisionService(mikaVisionModel(config, http), AndroidImageOps)

internal fun mikaVisionModel(config: MikaVisionConfig, http: OkHttpClient, guiAction: Boolean = false,
    temperature: Double? = if (guiAction) 0.0 else null,
) = OpenAiCompatModel(
        config.baseUrl,
        config.key.ifBlank { null },
        config.modelName,
        Capabilities(toolCalls = false, vision = true, contextTokens = 16_000, reasoning = false),
        http,
        maxOutputTokens = if (guiAction) 256 else null,
        temperature = temperature,
    )
