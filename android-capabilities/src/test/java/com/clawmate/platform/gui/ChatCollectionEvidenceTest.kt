package com.clawmate.platform.gui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class ChatCollectionEvidenceTest {
    @Test fun indirectWordingIsRetainedAndMissingFieldsSurvivePageChanges() = runBlocking {
        val read = ChatCollectionEvidence.plan("比较青岚耳机的通话、价格和续航") { _, _ ->
            """{"fields":["通话","价格","续航"]}"""
        }!!
        val first = "青岚在地铁里，对面说听不清我讲话。"
        val ad = "广告：白帆牌仅售 39 元"
        assertTrue(read.review(listOf(first, ad), "搜索结果第1页") { _, data ->
            val raw = Json.parseToJsonElement(data).jsonObject.getValue("candidates").jsonObject
            assertEquals(mapOf("T1" to first), raw.mapValues { it.value.jsonPrimitive.content })
            """{"Q1":["T1"],"Q2":["39 元"],"Q3":["T9"],"Q99":["T1"]}"""
        })
        assertEquals(listOf("价格","续航"), read.missing)
        val earlier = read.render()
        assertFalse(read.review(listOf("广告政策发生什么变化？"), "第2页") { _, _ -> "{bad response}" })
        assertEquals(earlier, read.render())
        assertTrue(read.review(listOf("青岚实付 289 元"), "商品详情") { _, _ ->
            """{"Q2":["T1"]}"""
        })
        assertEquals(listOf("续航"), read.missing)
        assertTrue(read.recordLines().any { "搜索结果第1页" in it && "听不清" in it })
        assertTrue(read.recordLines().any { "续航" in it && "暂未找到" in it })
        assertFalse(read.render().contains("39 元"))
    }

    @Test fun excludesInputEchoAndHandlesNonCollectionGoals() = runBlocking {
        assertEquals(listOf("对面听不清"), ChatCollectionEvidence.visibleLines(
            "当前 App:com.example\n耳 机\n屏幕可见文字(OCR):\n对面听不清\n对面听不清", listOf("耳机")))
        assertNull(ChatCollectionEvidence.plan("打开应用") { _, _ -> """{"fields":[]}""" })
    }

    /** Synthetic text only; the model profile is supplied by an opt-in test environment. */
    @Test fun configuredDeciderSeparatesObservedFieldsFromMissingOnes() = runBlocking {
        val path = System.getenv("CLAWMATE_COLLECTION_TEST_PROFILE")
        org.junit.Assume.assumeTrue(!path.isNullOrBlank())
        val profile = Json.parseToJsonElement(java.io.File(path!!).readText().removePrefix("\uFEFF")).jsonObject
        val url = profile.getValue("cloudDeciderBaseUrl").jsonPrimitive.content
        val uri = java.net.URI(url)
        val config = requireNotNull(com.clawmate.platform.mika.MikaVisionConfig.parse(url,
            profile.getValue("cloudDeciderModel").jsonPrimitive.content,
            profile.getValue("cloudDeciderApiKey").jsonPrimitive.content, "${uri.scheme}://${uri.rawAuthority}"))
        val client = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val reader = ChatCollectionEvidence.reader(com.clawmate.platform.mika.mikaVisionModel(config, client, temperature = 0.0))
        val result = requireNotNull(ChatCollectionEvidence.plan("查青岚耳机的通话表现、实际购买价格和一次充电使用时间。", reader))
        assertEquals(3, result.fields.size)
        val call = "青岚耳机带去坐地铁，对面总让我再说一遍。"
        val price = "青岚活动结算时我付了289元。"
        assertTrue(result.review(listOf(call, price, "银杉 Pro 的使用体验：充一次电通常可以连续使用36小时。", "广告：银杉耳机九折。"), "模拟商品页", reader))
        val text = result.render()
        assertTrue(text, call in text && price in text)
        assertEquals(listOf(result.fields[2]), result.missing)
        val rows = result.recordLines()
        assertTrue(rows.any { it.startsWith(result.fields[0]) && call in it })
        assertTrue(rows.any { it.startsWith(result.fields[1]) && price in it })
        assertFalse("36小时" in text)
    }
}
