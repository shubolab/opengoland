package dev.openidea.goide.generate.tests

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GoGenerateTestsDialogTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = true

    fun testDraftPreservesUncheckedRowsNamesAndNestedConcreteTypes() {
        val initial = inspection("function", "same")
        val dialog = GoGenerateTestsDialog(project, initial) { _, _, _, _ -> fail("The initial inspection must be reused") }
        var saved: JsonObject
        try {
            val table = components(dialog).filterIsInstance<JBTable>().single()
            assertEquals(false, table.model.getValueAt(0, 0))
            assertEquals(true, table.model.getValueAt(1, 0))
            table.model.setValueAt("TestAlreadyRenamed", 0, 2)
            table.model.setValueAt("TestGenericFresh", 1, 2)
            table.model.setValueAt("map[string]Pair[int, string]", 1, 3)
            val selected = dialog.selection().getAsJsonArray("selections")
            assertEquals(1, selected.size())
            assertEquals("TestGenericFresh", selected[0].asJsonObject.genString("testName"))
            assertEquals("map[string]Pair[int, string]", selected[0].asJsonObject.getAsJsonArray("typeArguments")[0].asString)
            saved = dialog.draft()
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val reopened = GoGenerateTestsDialog(project, initial, saved) { _, _, _, _ -> fail("A draft in the same inspection needs no reload") }
        try {
            val table = components(reopened).filterIsInstance<JBTable>().single()
            assertEquals(false, table.model.getValueAt(0, 0))
            assertEquals("TestAlreadyRenamed", table.model.getValueAt(0, 2))
            assertEquals("TestGenericFresh", reopened.selection().getAsJsonArray("selections")[0].asJsonObject.genString("testName"))
        } finally { reopened.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testOutOfOrderInspectionCannotEnablePreviewOrReplaceTheLatestScope() {
        data class Pending(val scope: String, val mode: String, val success: (JsonObject) -> Unit, val failure: (String) -> Unit)
        val pending = mutableListOf<Pending>()
        val dialog = GoGenerateTestsDialog(project, inspection("function", "same")) { scope, mode, success, failure -> pending += Pending(scope, mode, success, failure) }
        try {
            val scope = components(dialog).filterIsInstance<ComboBox<*>>().single { it.itemCount == 4 }
            scope.selectedIndex = 2
            scope.selectedIndex = 3
            assertEquals(listOf("file", "package"), pending.map { it.scope })
            assertFalse(dialog.isOKActionEnabled)
            pending[0].success(inspection("file", "same"))
            assertFalse("A stale success cannot finish the latest request", dialog.isOKActionEnabled)
            pending[1].success(inspection("package", "same"))
            assertTrue(dialog.isOKActionEnabled)
            assertEquals("package", dialog.draft().genString("scope"))
            scope.selectedIndex = 1
            pending.last().failure("Go analysis failed")
            assertFalse(dialog.isDisposed)
            assertFalse(dialog.isOKActionEnabled)
            val mode = components(dialog).filterIsInstance<ComboBox<*>>().single { it.itemCount == 2 }
            mode.selectedIndex = 1
            pending.last().success(inspection("function", "external"))
            assertTrue(dialog.isOKActionEnabled)
            assertEquals("external", dialog.draft().genString("packageMode"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        pending.first().success(inspection("file", "same"))
        assertTrue(dialog.isDisposed)
    }

    fun testEmptyTemplateAndOptionalDestinationRemainAnExplicitPlanSelection() {
        val dialog = GoGenerateTestsDialog(project, inspection("empty", "same")) { _, _, _, _ -> fail("No reload expected") }
        try {
            val fields = components(dialog).filterIsInstance<JBTextField>()
            fields.single { it.text == "TestSomething" }.text = "TestFresh"
            assertFalse(dialog.selection().has("destinationUri"))
            fields.single { it.text.isEmpty() }.text = "generated_linux_test.go"
            val selected = dialog.selection()
            assertEquals("TestFresh", selected.genString("emptyName"))
            assertEquals(0, selected.getAsJsonArray("selections").size())
            assertEquals("file:///tmp/generated_linux_test.go", selected.genString("destinationUri"))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    fun testDraftScopeRefreshStartedBeforeShowingCompletesInsideDialogModality() {
        var success: ((JsonObject) -> Unit)? = null
        val draft = JsonObject().apply { addProperty("scope", "package"); addProperty("packageMode", "external") }
        val dialog = GoGenerateTestsDialog(project, inspection("function", "same"), draft) { scope, mode, callback, _ ->
            assertEquals("package", scope); assertEquals("external", mode); success = callback
        }
        val modal = dialog.disposable
        LaterInvocator.enterModal(modal)
        try {
            assertFalse(dialog.isOKActionEnabled)
            CompletableFuture.runAsync { success!!(inspection("package", "external")) }.get(5, TimeUnit.SECONDS)
            UIUtil.dispatchAllInvocationEvents()
            assertTrue("A pre-show inspection must update only its own dialog even after modality starts", dialog.isOKActionEnabled)
            assertEquals("package", dialog.draft().genString("scope"))
        } finally {
            LaterInvocator.leaveModal(modal)
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testExternalPackageLeavesPrivateDeclarationsUncheckedAndSamePackageCanSelectThem() {
        fun response(mode: String) = inspection("file", mode).apply {
            getAsJsonArray("symbols")[0].asJsonObject.apply {
                addProperty("exported", false); addProperty("existingTest", false)
            }
        }
        val dialog = GoGenerateTestsDialog(project, response("external")) { _, mode, success, _ -> success(response(mode)) }
        try {
            val table = components(dialog).filterIsInstance<JBTable>().single()
            assertEquals(false, table.model.getValueAt(0, 0))
            assertFalse(table.model.isCellEditable(0, 0))
            assertEquals("Package private", table.model.getValueAt(0, 4))
            table.model.setValueAt(true, 0, 0)
            assertEquals(false, table.model.getValueAt(0, 0))
            assertEquals(true, table.model.getValueAt(1, 0))
            val mode = components(dialog).filterIsInstance<ComboBox<*>>().single { it.itemCount == 2 }
            mode.selectedIndex = 0
            assertTrue(table.model.isCellEditable(0, 0))
            table.model.setValueAt(true, 0, 0)
            assertEquals(true, table.model.getValueAt(0, 0))
        } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
    }

    private fun inspection(scope: String, mode: String) = JsonObject().apply {
        addProperty("inspectionId", "$scope-$mode"); addProperty("configurationDigest", "digest")
        addProperty("scope", scope); addProperty("packageMode", mode)
        addProperty("packageName", "fixture"); addProperty("packagePath", "example.org/fixture")
        addProperty("defaultDestinationUri", "file:///tmp/subject_linux_test.go")
        add("conflicts", JsonArray())
        add("symbols", JsonArray().apply {
            if (scope != "empty") {
                add(symbol("already", "TestAlready", existing = true, generic = false))
                add(symbol("generic", "TestGeneric", existing = false, generic = true))
            }
        })
    }

    private fun symbol(id: String, test: String, existing: Boolean, generic: Boolean) = JsonObject().apply {
        addProperty("symbolId", id); addProperty("name", id); addProperty("suggestedTestName", test)
        addProperty("existingTest", existing); addProperty("needsTypeArguments", generic)
        addProperty("exported", true)
        add("parameters", JsonArray()); add("results", JsonArray())
        add("typeParameters", JsonArray().apply { if (generic) add(JsonObject().apply { addProperty("name", "T"); addProperty("constraint", "any") }) })
    }

    private fun components(dialog: GoGenerateTestsDialog): List<Component> {
        // DialogWrapper.init builds the actual Swing hierarchy, but its SDK headless
        // peer intentionally discards setContentPane and returns null from getContentPane.
        val field = GoGenerateTestsDialog::class.java.getDeclaredField("panel").apply { isAccessible = true }
        return components(field.get(dialog) as Container)
    }

    private fun components(container: Container): List<Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
}
