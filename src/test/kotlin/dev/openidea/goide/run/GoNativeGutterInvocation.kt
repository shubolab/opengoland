package dev.openidea.goide.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerProvider
import com.intellij.idea.AppMode
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.IndexingTestUtil
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Invoke the real platform popup child; the platform's unit-test guard prevents process execution. */
internal fun invokeNativeGoGutter(file: PsiFile, anchor: PsiElement, executorId: String, directory: String): GoRunParameters {
    assertTrue(ApplicationManager.getApplication().isUnitTestMode)
    assertFalse(AppMode.isRunningFromDevBuild(), "The SDK unit-test execution guard must prevent launching Go processes")
    val project = file.project
    // A freshly detected corpus file queues VFS property-push/indexing work.
    // Context actions legitimately hide non-dumb-aware configuration types then.
    // Settle that fixture work before checking the ordinary action workflow.
    IndexingTestUtil.waitUntilIndexesAreReady(project)
    val manager = RunManager.getInstance(project)
    val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
        requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    }
    val registered = CountDownLatch(1)
    val expectedDirectory = Path.of(directory).normalize()
    require(expectedDirectory.isAbsolute)
    val captured = AtomicReference<RunnerAndConfigurationSettings>()
    val connection = project.messageBus.connect()
    var editor: Editor? = null
    var previous: RunnerAndConfigurationSettings? = null
    try {
        connection.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) {
                val configuration = settings.configuration as? GoRunConfiguration ?: return
                // VFS paths use '/', while a native Windows Path uses '\\'.
                // This listener runs on EDT: compare syntax without filesystem I/O.
                val actualDirectory = runCatching { Path.of(configuration.parameters.workingDirectory).normalize() }.getOrNull()
                if (actualDirectory == expectedDirectory) {
                    captured.set(settings)
                    registered.countDown()
                }
            }
        })
        gutterOnEdt {
            previous = manager.selectedConfiguration
            editor = EditorFactory.getInstance().createEditor(document, project)
            // Keep the caret away from the clicked target: the renderer's Location must win.
            editor!!.caretModel.moveToOffset(document.textLength)
            val data = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor!!)
                .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                .build()
            val marker = requireNotNull(RunLineMarkerProvider().getLineMarkerInfo(anchor))
            val renderer = requireNotNull(marker.createGutterRenderer())
            assertTrue(renderer.isNavigateAction)
            assertNull(renderer.clickAction, "Run + Debug use the platform popup branch, rather than a direct click action")
            val actions = requireNotNull(renderer.popupMenuActions).getChildren(null)
            assertEquals(2, actions.size)
            val action = actions.single {
                val executor = (it as? ActionWithDelegate<*>)?.delegate as? ExecutorAction
                executor?.executor?.id == executorId
            }
            val event = AnActionEvent.createFromAnAction(action, null, "ICON_NAVIGATION_SECONDARY_BUTTON", data)
            action.update(event)
            assertTrue(event.presentation.isEnabledAndVisible, event.presentation.text.orEmpty())
            action.actionPerformed(event)
        }
        assertTrue(registered.await(10, TimeUnit.SECONDS), "The real $executorId gutter action must register its native configuration")
        var result: GoRunParameters? = null
        gutterOnEdt {
            val settings = requireNotNull(captured.get())
            if (manager.shouldSetRunConfigurationFromContext()) assertEquals(settings, manager.selectedConfiguration)
            val configuration = settings.configuration as GoRunConfiguration
            assertEquals(GoRunKind.TEST, configuration.kind)
            result = configuration.parameters.copy(environment = configuration.parameters.environment.toMutableMap())
        }
        return requireNotNull(result)
    } finally {
        connection.disconnect()
        gutterOnEdt {
            captured.get()?.let(manager::removeConfiguration)
            manager.selectedConfiguration = previous
            editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        }
    }
}

private fun gutterOnEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> {
    WriteIntentReadAction.run(Runnable(action))
}
