package com.clawmate.platform.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MikaVisionConfigTest {
    @Test fun parsesConfiguredVisionModelAndTrimsValues() {
        val config = MikaVisionConfig.parse(" https://api.example.com/v1 ", " qwen-vl-max ", " sk-test ")
        assertEquals("https://api.example.com/v1", config?.baseUrl)
        assertEquals("qwen-vl-max", config?.modelName)
        assertEquals("sk-test", config?.key)
    }

    @Test fun acceptsMissingKeyBecauseSomeGatewaysAuthenticateByPath() {
        val config = MikaVisionConfig.parse("https://api.example.com", "vision-model", "")
        assertEquals("https://api.example.com/v1", config?.baseUrl)
        assertEquals("", config?.key)
    }

    @Test fun blankNameSelectsEndpointDefault() {
        assertEquals("", MikaVisionConfig.parse("https://api.example.com", "  ", "k")?.modelName)
    }

    @Test fun workflowOriginAllowsOnlyItsLiteralAuthorityAndApiPath() {
        val origin = "http://127.0.0.1:19872"
        assertEquals("$origin/v1", MikaVisionConfig.parse("$origin/v1/chat/completions", "", "k", origin)?.baseUrl)
        for (url in listOf("http://127.0.0.1:19873", "$origin/elsewhere", "$origin/other/../v1",
                "$origin/v1///", "$origin/%76%31", "$origin/v1%2fchat/completions", "http://@127.0.0.1:19872/v1", "$origin/v1?q=1", "$origin/#token",
                "http://user@127.0.0.1:19872/v1", "http://example.com:19872")) {
            assertNull(MikaVisionConfig.parse(url, "", "k", origin))
        }
    }

    @Test fun plannerOnlyAcceptsItsOwnPinnedOriginAndCompatiblePaths() {
        val origin = "http://127.0.0.1:9003"
        assertEquals("$origin/v1", MikaEndpoints.planner("$origin/v1/chat/completions", origin)?.second)
        assertNull(MikaEndpoints.planner("http://127.0.0.1:8080", origin))
        assertNull(MikaEndpoints.planner("$origin/v1?key=other", origin))
        assertNull(MikaEndpoints.planner("$origin/elsewhere/../v1", origin))
        assertNull(MikaEndpoints.planner(origin, ""))
        assertEquals("https://model.example/v1", MikaEndpoints.planner("https://model.example", "")?.second)
    }

    @Test fun rejectsPlainHttpAndOversizedFields() {
        assertNull("plain http", MikaVisionConfig.parse("http://api.example.com", "vision", "k"))
        assertNull("model over 180", MikaVisionConfig.parse("https://api.example.com", "m".repeat(181), "k"))
        assertNull("key over 4096", MikaVisionConfig.parse("https://api.example.com", "vision", "k".repeat(4097)))
    }
}
