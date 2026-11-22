package com.clawmate.shared

import android.util.Base64
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Carries the upstream Planner response to the shared JSON/SSE decoder without following redirects. */
internal class ExperiencePlannerTransport(private val origin: String) : Closeable {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private class Job(val call: Call) {
        val chunks = ArrayBlockingQueue<String>(12)
        @Volatile var result = ""
        @Volatile var code = 0
        @Volatile var done = false
        @Volatile var error = ""
        @Volatile var lastPoll = SystemClock.elapsedRealtime()
    }
    private val jobs = mutableMapOf<String, Job>()
    private var closed = false

    @Synchronized fun start(url: String, key: String, body: String, timeout: Int): String {
        check(!closed && origin.isNotEmpty() && url == "$origin/v1/chat/completions") { "Planner 地址未获放行" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..(12 * 1024 * 1024) && key.length <= 4096) { "Planner 请求大小无效" }
        val now = SystemClock.elapsedRealtime()
        jobs.filterValues { now - it.lastPoll > 30000L }.keys.toList().forEach(::cancel)
        check(jobs.size < 8) { "正在处理的 Planner 请求过多" }
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${key.ifEmpty { "EMPTY" }}")
            .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(req)
        call.timeout().timeout(timeout.coerceIn(1000, 130000).toLong(), TimeUnit.MILLISECONDS)
        val streaming = JSONObject(body).optBoolean("stream", false)
        val job = Job(call)
        val id = UUID.randomUUID().toString()
        jobs[id] = job
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                job.error = if (e is java.io.InterruptedIOException) "连接 Planner 超时" else "无法连接 Planner 服务"
                job.done = true
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        job.code = it.code
                        val input = requireNotNull(it.body).byteStream()
                        val result = if (streaming) null else ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (!call.isCanceled()) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            check(total <= 4 * 1024 * 1024) { "Planner 响应过长" }
                            if (result != null) {
                                result.write(buffer, 0, n)
                                continue
                            }
                            val chunk = Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP)
                            while (!job.chunks.offer(chunk, 200, TimeUnit.MILLISECONDS)) {
                                if (call.isCanceled() || SystemClock.elapsedRealtime() - job.lastPoll > 15000) throw IOException()
                            }
                        }
                        if (result != null) job.result = result.toString(Charsets.UTF_8.name())
                    }
                } catch (err: Exception) {
                    job.error = when (err) {
                        is java.io.InterruptedIOException -> "连接 Planner 超时"
                        is IllegalStateException -> "Planner 响应过长或为空"
                        else -> "Planner 响应已中断"
                    }
                } finally { job.done = true }
            }
        })
        return id
    }

    @Synchronized fun poll(id: String): String {
        val job = jobs[id] ?: error("Planner 请求已结束")
        job.lastPoll = SystemClock.elapsedRealtime()
        val parts = mutableListOf<String>()
        job.chunks.drainTo(parts)
        val done = job.done && job.chunks.isEmpty()
        if (done) jobs.remove(id)
        return JSONObject().put("code", job.code).put("chunks", JSONArray(parts))
            .put("result", if (done) job.result else "").put("done", done).put("error", job.error).toString()
    }

    @Synchronized fun cancel(id: String) { jobs.remove(id)?.call?.cancel() }
    @Synchronized override fun close() { closed = true; jobs.keys.toList().forEach(::cancel) }
}
