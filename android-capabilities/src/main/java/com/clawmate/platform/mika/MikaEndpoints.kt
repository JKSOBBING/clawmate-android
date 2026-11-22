package com.clawmate.platform.mika

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Mika 的 OpenAI 兼容端点校验与规范化。
 *
 * 与 MikaPhoneEntry 之前的实现一致：只接受 HTTPS、不带用户名/密码/查询串/片段的地址；
 * 用户填 `.../v1` 或 `.../v1/chat/completions` 时原样使用，否则补 `/v1`。
 * 返回端点本身（用于界面展示主机名）和拼 `/chat/completions` 用的 base。
 */
object MikaEndpoints {
    fun experienceProfile(context: android.content.Context): org.json.JSONObject =
        if (context.packageName == "com.clawmate.experience") {
            context.assets.open("experience-models.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
        } else org.json.JSONObject()

    fun planner(raw: String, origin: String): Pair<HttpUrl, String>? {
        parse(raw)?.let { return it }
        if (!raw.trim().startsWith("http://")) return null
        if (!MikaVisionConfig.permitsWorkflowHttp(raw, origin)) return null
        val base = "$origin/v1"
        return (base.toHttpUrlOrNull() ?: return null) to base
    }

    fun parse(raw: String): Pair<HttpUrl, String>? {
        val endpoint = raw.trim().toHttpUrlOrNull() ?: return null
        if (!endpoint.isHttps || endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty() ||
            endpoint.query != null || endpoint.fragment != null
        ) return null
        val withoutCompletions = endpoint.toString().trimEnd('/').removeSuffix("/chat/completions")
        val base = if (withoutCompletions.endsWith("/v1") || endpoint.encodedPath.endsWith("/chat/completions")) {
            withoutCompletions
        } else {
            "$withoutCompletions/v1"
        }
        return endpoint to base
    }
}
