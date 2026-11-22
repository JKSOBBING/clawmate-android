package com.clawmate.shared

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.sources.PersonalSourceEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersonalSourcesDeviceTest {
    @Test fun publicUpcomingCalendarItemIsVisibleInHomeReminders() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val expected = InstrumentationRegistry.getArguments().getString("upcomingPublicHoliday")
        org.junit.Assume.assumeTrue(inst.targetContext.packageName == "com.clawmate.experience" && !expected.isNullOrBlank())
        val context = inst.targetContext
        org.junit.Assume.assumeFalse("Preserve any existing synchronized calendar", File(context.filesDir, "personal-sources/calendar.json").exists())
        androidx.test.core.app.ActivityScenario.launch(SharedActivity::class.java).use { scene ->
            lateinit var port: PlatformBridge
            scene.onActivity { activity ->
                port = SharedActivity::class.java.getDeclaredField("platform").apply { isAccessible = true }.get(activity) as PlatformBridge
            }
            val original = port.sourcesOptions()
            org.junit.Assume.assumeFalse("Only use an unconfigured calendar", JSONObject(original).optBoolean("calendarEnabled"))
            try {
                val enabled = JSONObject(original).put("calendarEnabled", true).put("holidayEnabled", true).put("festivalEnabled", true)
                assertTrue(JSONObject(port.sourcesSaveOptions("calendar", enabled.toString())).getBoolean("ok"))
                val id = port.sourcesStart("calendar", """{"quick":true}""")
                var job = JSONObject(port.sourcesPoll(id))
                val deadline = android.os.SystemClock.elapsedRealtime() + 12000
                while (job.optString("state") == "running" && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(120)
                    job = JSONObject(port.sourcesPoll(id))
                }
                assertEquals("done", job.optString("state"))
                val result = JSONObject(job.getString("value"))
                assertTrue(result.getBoolean("ok"))
                val items = JSONObject(result.getString("value")).getJSONArray("items")
                val publicItemAvailable = (0 until items.length()).any { i -> items.getJSONObject(i).getString("title").contains(expected!!) }
                inst.sendStatus(2, android.os.Bundle().apply { putBoolean("upcomingPublicItemInSource", publicItemAvailable) })
                assertTrue("Selected public holiday must exist in the active seven-day source", publicItemAvailable)
                scene.recreate()
                if (!DeviceTestTimeouts.clickNavigation("首页")) DevicePageControls.open("首页")
                DevicePageControls.open("为你提醒")
                DevicePageControls.expect(requireNotNull(expected))
                inst.sendStatus(2, android.os.Bundle().apply { putString("homeUpcomingPublicReminder", expected) })
            } finally {
                assertTrue(JSONObject(port.sourcesSaveOptions("calendar", original)).getBoolean("ok"))
            }
        }
    }

    @Test fun explicitlySelectedCalendarMarkerIsReadableOnExperienceDevice() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val marker = InstrumentationRegistry.getArguments().getString("publicCalendarMarker")
        org.junit.Assume.assumeTrue(context.packageName == "com.clawmate.experience" && !marker.isNullOrBlank())
        require(marker!!.length in 1..80 && marker.none { it == '%' || it == '_' })
        androidx.test.core.app.ActivityScenario.launch(SharedActivity::class.java).use { scene ->
            lateinit var bridge: PlatformBridge
            scene.onActivity { activity ->
                bridge = SharedActivity::class.java.getDeclaredField("platform").apply { isAccessible = true }.get(activity) as PlatformBridge
                bridge.sourcesPermissionStart(false)
            }
            val until = android.os.SystemClock.elapsedRealtime() + 25000
            fun allowButton(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.packageName?.toString() in setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller") &&
                    node.viewIdResourceName?.endsWith(":id/permission_allow_button") == true) return node
                for (i in 0 until node.childCount) allowButton(node.getChild(i))?.let { return it }
                return null
            }
            while (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                android.os.SystemClock.elapsedRealtime() < until) {
                allowButton(inst.uiAutomation.rootInActiveWindow)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(150)
            }
            assertEquals("Read-only calendar request must be granted through the normal consent dialog",
                android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR))
            val day = java.time.LocalDate.parse(InstrumentationRegistry.getArguments().getString("publicCalendarDate"))
            val zone = java.time.ZoneId.systemDefault()
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri, day.atStartOfDay(zone).toInstant().toEpochMilli())
            android.content.ContentUris.appendId(uri, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val words = marker.split(' ').filter(String::isNotEmpty)
            val pattern = words.joinToString("%", prefix = "%", postfix = "%")
            context.contentResolver.query(uri.build(), arrayOf("event_id"), "title LIKE ?", arrayOf(pattern), null).use { cursor ->
                val count = requireNotNull(cursor).count
                inst.sendStatus(2, android.os.Bundle().apply { putInt("selectedCalendarMarkerCount", count) })
                assertTrue("The selected calendar marker was not present in this provider", count > 0)
            }
        }
    }

    @Test fun experienceEntryShowsItsExpectedService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val route = InstrumentationRegistry.getArguments().getString("experienceRoute")
        org.junit.Assume.assumeTrue(instrumentation.targetContext.packageName == "com.clawmate.experience" && route != null)
        val labels = when (route) {
            "calendar" -> listOf("汇总", "日历分析", "读取手机系统日历")
            "mail" -> listOf("汇总", "邮箱分析", "邮箱账号")
            "domain" -> listOf("汇总", "全部领域", "差旅")
            "hdc" -> listOf("我的", "手机 HDC", "打开手机控制设置", "应用访问")
            "codex" -> listOf("Codex", "创建连接")
            "task" -> listOf("任务", "手机自己控制")
            "privacy" -> listOf("我的", "个人与隐私", "隐私政策")
            else -> error("Unknown release check")
        }
        androidx.test.core.app.ActivityScenario.launch(SharedActivity::class.java).use {
            labels.dropLast(1).forEach { DevicePageControls.open(it) }
            DevicePageControls.expect(labels.last())
        }
    }

    @Test fun isolatedExperienceProfileCanBeSavedAndReopenedFromSummary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeTrue(context.packageName == "com.clawmate.experience")
        val state = File(context.filesDir, "files/people-memory/subjects.json")
        org.junit.Assume.assumeFalse("Only use a fresh profile store", state.exists())
        val example = "ClawMateQADemo"
        try {
            androidx.test.core.app.ActivityScenario.launch(SharedActivity::class.java).use { scene ->
                fun enterProfile() {
                    if (!DeviceTestTimeouts.clickNavigation("汇总")) DevicePageControls.open("汇总")
                    DevicePageControls.open("人物资料")
                    DevicePageControls.open("本人")
                }
                enterProfile()
                DevicePageControls.open("填写姓名")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("input text $example")
                ).use { it.readBytes() }
                DevicePageControls.open("保存")
                val deadline = android.os.SystemClock.elapsedRealtime() + 6000
                while (!state.isFile && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                assertTrue("Profile editor must persist the new subject", state.isFile)
                val people = JSONObject(state.readText()).getJSONArray("people")
                val self = (0 until people.length()).map(people::getJSONObject).single { it.getString("id") == "person:self" }
                assertEquals(example, self.getJSONObject("values").getString("name"))
                scene.recreate()
                enterProfile()
                DevicePageControls.open(example)
            }
        } finally {
            if (state.isFile && state.readText().contains(example)) state.delete()
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun calendarQueriesUseSelectedSourcesAndStoredWindowWithoutWritingProvider() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "calendar-fixture-${System.nanoTime()}").apply { mkdirs() }
        var eventReads = 0
        var granted = true
        var queriedStart = 0L
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: android.net.Uri): String? = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = error("read only")
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = error("read only")
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = error("read only")
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor {
                val cursor = android.database.MatrixCursor(projection!!)
                if (uri == android.provider.CalendarContract.Calendars.CONTENT_URI) cursor.addRow(arrayOf(7L, "Fixture calendar", "LOCAL", "fixture", 700, 1))
                else {
                    eventReads++
                    assertEquals("calendar_id IN (?)", selection); assertArrayEquals(arrayOf("7"), args)
                    queriedStart = uri.pathSegments[uri.pathSegments.size - 2].toLong()
                    val now = System.currentTimeMillis()
                    cursor.addRow(arrayOf(1L, 7L, "Fixture event", "Synthetic body", "", now, now + 3600000, 0, "Asia/Shanghai"))
                }
                return cursor
            }
        }
        val resolver = android.content.ContentResolver.wrap(provider)
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(directory.name + name, mode)
            override fun getContentResolver() = resolver
            override fun checkSelfPermission(permission: String) = if (granted) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED
        }
        PersonalSourceEngine(isolated).use { engine ->
            try {
                val listed = JSONObject(engine.execute("calendar", "{\"listOnly\":true}")).getJSONArray("sources")
                assertTrue((0 until listed.length()).any { listed.getJSONObject(it).optJSONArray("members")?.optLong(0) == 7L })
                assertEquals(0, eventReads)
                for (days in listOf(0, 30, 90, 365)) {
                    engine.saveOptions("calendar", "{\"calendarEnabled\":true,\"historyDays\":$days,\"selected\":[7]}")
                    val raw = engine.execute("calendar", "{}")
                    val result = JSONObject(raw)
                    val items = result.getJSONArray("items")
                    assertEquals(1, (0 until items.length()).count { items.getJSONObject(it).optString("source") == "system" })
                    val expected = java.time.LocalDate.now().minusDays(days.toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    assertEquals(expected, queriedStart); assertEquals(expected, result.getLong("windowStart"))
                    engine.write("calendar", raw)
                    assertEquals(items.length(), JSONObject(engine.read("calendar")).getJSONArray("items").length())
                }
                granted = false
                assertEquals("{}", engine.read("calendar"))
                assertThrows(IllegalStateException::class.java) { engine.execute("calendar", "{}") }
                assertEquals(4, eventReads)
            } finally {
                base.deleteSharedPreferences(directory.name + "personal_sources")
                base.deleteSharedPreferences(directory.name + "personal_source_options")
                directory.walkBottomUp().forEach { it.delete() }
            }
        }
    }

    @Test fun encryptedCredentialsAndAtomicSnapshotsAreIsolatedAndClearable() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "sources-fixture-${System.nanoTime()}").apply { mkdirs() }
        val prefsName = directory.name
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefsName + "-" + name, mode)
        }
        val engine = PersonalSourceEngine(isolated)
        try {
            assertFalse(JSONObject(engine.options()).getBoolean("calendarEnabled"))
            assertEquals(90, JSONObject(engine.options()).getInt("historyDays"))
            engine.saveOptions("calendar", "{\"calendarEnabled\":true,\"historyDays\":30,\"selected\":[7]}")
            engine.saveOptions("mail", "{\"maxMessages\":20}")
            assertThrows(IllegalArgumentException::class.java) { engine.saveOptions("mail", "{\"maxMessages\":999}") }
            assertThrows(IllegalArgumentException::class.java) { engine.saveOptions("calendar", "{\"calendarEnabled\":true,\"historyDays\":999,\"selected\":[]}") }
            engine.saveCredentials("imap.example.invalid", 993, "fixture@example.invalid", "fixture-secret")
            val preferences = base.getSharedPreferences(prefsName + "-personal_sources", Context.MODE_PRIVATE)
            assertFalse(preferences.getString("secret", "")!!.contains("fixture-secret"))
            assertFalse(engine.settings().contains("fixture-secret"))
            engine.saveCredentials("imap.example.invalid", 993, "fixture@example.invalid", "") // decrypt retained secret
            engine.write("mail", JSONObject().put("syncedAt", 123).put("items", org.json.JSONArray()).toString())
            assertEquals(123, JSONObject(engine.read("mail")).getInt("syncedAt"))
            engine.configureOAuth("google", "fixture@example.invalid")
            assertFalse(preferences.contains("secret")); assertEquals("{}", engine.read("mail"))
            assertEquals("imap.gmail.com", JSONObject(engine.settings()).getString("host"))
            assertEquals("google", JSONObject(engine.settings()).getString("mode"))
            assertThrows(IllegalStateException::class.java) { engine.oauthToken("google", "fixture@example.invalid") }
            engine.configureOAuth("microsoft", "fixture@example.invalid", "public-fixture-id", "common")
            assertEquals("outlook.office365.com", JSONObject(engine.settings()).getString("host"))
            assertEquals("public-fixture-id", JSONObject(engine.settings()).getString("client"))
            engine.forgetMail()
            val restored = JSONObject(PersonalSourceEngine(isolated).use { it.options() })
            assertEquals(30, restored.getInt("historyDays")); assertEquals(20, restored.getInt("maxMessages"))
            assertTrue(restored.getBoolean("calendarEnabled")); assertEquals(7, restored.getJSONArray("selected").getInt(0))
            // Controlled snapshot: disabling calendar clears only the app-owned copy and retains choices.
            File(directory, "personal-sources/calendar.json").writeText("{\"items\":[],\"syncedAt\":123}")
            engine.saveOptions("calendar", "{\"calendarEnabled\":false,\"historyDays\":30,\"selected\":[7]}")
            assertFalse(File(directory, "personal-sources/calendar.json").exists())
            assertEquals("{}", engine.read("calendar"))
            assertEquals("{}", engine.read("mail"))
            assertEquals("", JSONObject(engine.settings()).getString("username"))
            if (!engine.calendarGranted()) {
                assertEquals("{}", engine.read("calendar"))
                assertThrows(IllegalStateException::class.java) { engine.execute("calendar", "{\"selected\":[]}") }
            }
        } finally {
            engine.close(); base.deleteSharedPreferences(prefsName + "-personal_sources"); base.deleteSharedPreferences(prefsName + "-personal_source_options")
            directory.walkBottomUp().forEach { it.delete() }
        }
    }
}
