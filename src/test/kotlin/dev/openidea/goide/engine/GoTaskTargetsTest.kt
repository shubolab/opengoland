package dev.openidea.goide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBList
import java.nio.file.Path

class GoTaskTargetsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = true
    private val base = Path.of(System.getProperty("java.io.tmpdir"), "workspace", "中文 😀").toAbsolutePath()

    fun testAnAmbiguousWorkspaceRequiresAnExplicitNativeSelectionFromThisInspection() {
        val inspection = GoTaskTargetsCodec.decode(response(), "tidy")
        assertNull(inspection.defaultTargetId)
        val dialog = GoTaskTargetDialog(project, inspection, "Go Mod Tidy")
        try {
            assertFalse("An arbitrary first module must not become the target", dialog.isOKActionEnabled)
            assertTrue(runCatching { dialog.selection() }.isFailure)
            @Suppress("UNCHECKED_CAST")
            val list = GoTaskTargetDialog::class.java.getDeclaredField("choices").apply { isAccessible = true }.get(dialog) as JBList<GoTaskTarget>
            list.selectedIndex = 1
            assertTrue(dialog.isOKActionEnabled)
            val selected = dialog.selection()
            assertEquals("module-two", selected.id)
            assertEquals(base.resolve("two").toString(), selected.cwd)
            assertEquals("inspection-one", inspection.selection(selected).get("inspectionId").asString)
            assertEquals("module-two", inspection.selection(selected).get("targetId").asString)
            assertTrue(runCatching { inspection.selection(selected.copy(id = "another-inspection-target")) }.isFailure)
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testMalformedDefaultsCommandsDuplicateIdsAndUnusablePathsCannotSelectATask() {
        fun rejects(change: (JsonObject) -> Unit) {
            val value = response().also(change)
            assertTrue(value.toString(), runCatching { GoTaskTargetsCodec.decode(value, "tidy") }.isFailure)
        }
        rejects { it.addProperty("defaultTargetId", "not-in-this-inspection") }
        rejects { it.addProperty("command", "vendor") }
        rejects { it.addProperty("inspectionId", "") }
        rejects { it.getAsJsonArray("targets")[1].asJsonObject.addProperty("id", "module-one") }
        rejects { it.getAsJsonArray("targets")[0].asJsonObject.addProperty("cwd", "../another-module") }
        rejects { it.getAsJsonArray("targets")[0].asJsonObject.addProperty("kind", "arbitrary-command") }
        val value = response().apply { addProperty("defaultTargetId", "module-two") }
        val parsed = GoTaskTargetsCodec.decode(value, "tidy")
        assertEquals("module-two", parsed.defaultTargetId)
        assertEquals("Keep the other module unchanged", parsed.targets[1].description)
        assertEquals(listOf("Explicitly choose a task scope"), parsed.warnings)
    }

    private fun response() = JsonObject().apply {
        addProperty("inspectionId", "inspection-one"); addProperty("command", "tidy")
        add("warnings", JsonArray().apply { add("Explicitly choose a task scope") })
        add("targets", JsonArray().apply {
            listOf("one", "two").forEach { name -> add(JsonObject().apply {
                addProperty("id", "module-$name"); addProperty("kind", "module")
                addProperty("label", "example.org/$name"); addProperty("cwd", base.resolve(name).toString())
                addProperty("description", "Keep the other module unchanged")
            }) }
        })
    }
}
