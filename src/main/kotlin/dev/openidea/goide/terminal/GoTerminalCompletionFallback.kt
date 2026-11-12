package dev.openidea.goide.terminal

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.DataManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.impl.TerminalViewImpl
import com.intellij.util.Alarm
import java.awt.KeyboardFocusManager

/**
 * Provides deterministic Go command completion for reworked terminals.
 *
 * IntelliJ 2026.1 may not invoke its built-in command completion for some shell
 * sessions, including when customized zsh never finishes shell integration. This
 * fallback reads the active command line and rewrites only that shell edit line
 * through the terminal's input channel.
 */
class GoTerminalCompletionFallback : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.getUserData(LISTENER_DISPOSABLE) != null) return

        val disposable = Disposer.newDisposable("Go terminal command completion fallback")
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
        val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!isReworkedTerminalEditor(editor)) return
                alarm.cancelAllRequests()
                alarm.addRequest({ invokeCompletion(editor) }, COMPLETION_DELAY_MS)
            }
        }
        editor.document.addDocumentListener(documentListener, disposable)
        editor.putUserData(LISTENER_DISPOSABLE, disposable)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val disposable = editor.getUserData(LISTENER_DISPOSABLE) ?: return
        editor.putUserData(LISTENER_DISPOSABLE, null)
        Disposer.dispose(disposable)
    }

    private fun invokeCompletion(editor: Editor) {
        val project = editor.project ?: return
        val dataManager = DataManager.getInstance()
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val view = listOfNotNull(focusOwner, editor.contentComponent)
            .asSequence()
            .map(dataManager::getDataContext)
            .mapNotNull(TerminalView.DATA_KEY::getData)
            .firstOrNull() ?: return
        val outputModel = view.outputModels.active.value
        val cursorOffset = outputModel.cursorOffset
        val line = outputModel.getLineByOffset(cursorOffset)
        val lineStart = outputModel.getStartOfLine(line)
        val commandLine = outputModel.getText(lineStart, cursorOffset).toString()
        val completedCommand = resolveGoTerminalCompletion(commandLine) ?: return

        if (LookupManager.getActiveLookup(editor) != null) {
            LookupManager.getInstance(project).hideActiveLookup()
        }
        sendReplacement(view, completedCommand)
    }

    private fun sendReplacement(view: TerminalView, command: String) {
        val implementation = view as? TerminalViewImpl ?: return
        val accessor = TerminalViewImpl::class.java.getMethod(
            "access\$getTerminalInput\$p",
            TerminalViewImpl::class.java,
        )
        val input = accessor.invoke(null, implementation) ?: return
        val clearLine = terminalLineClearSequence(
            System.getProperty("os.name").orEmpty(),
            command.length,
        )
        input.javaClass.getMethod("sendBytes", ByteArray::class.java).invoke(input, clearLine)
        input.javaClass.getMethod("sendString", String::class.java).invoke(input, command)
    }

    private companion object {
        const val COMPLETION_DELAY_MS = 180
        val LISTENER_DISPOSABLE: Key<com.intellij.openapi.Disposable> =
            Key.create("openideago.terminal.completion.fallback")
        val TERMINAL_EDITOR_PREDICATE: (Editor) -> Boolean = run {
            try {
                val utilityClass = Class.forName(
                    "org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils",
                    true,
                    TerminalView::class.java.classLoader,
                )
                val utility = utilityClass.getField("INSTANCE").get(null)
                val method = utilityClass.getMethod("isReworkedTerminalEditor", Editor::class.java)
                val predicate: (Editor) -> Boolean = { editor -> method.invoke(utility, editor) as Boolean }
                predicate
            } catch (_: ReflectiveOperationException) {
                { _: Editor -> true }
            }
        }

        fun isReworkedTerminalEditor(editor: Editor): Boolean = TERMINAL_EDITOR_PREDICATE(editor)
    }
}

/**
 * Unix shells normally bind Ctrl-U to "delete to line start". Windows cmd and
 * PowerShell screens do not share that binding, so use shell-editor backspaces
 * there. The observed line may already include the prompt; extra backspaces at
 * the line start are ignored by the shell's editable region.
 */
internal fun terminalLineClearSequence(osName: String, currentLineLength: Int): ByteArray =
    if (osName.startsWith("Windows", ignoreCase = true)) {
        ByteArray(currentLineLength.coerceAtLeast(1)) { 0x08 }
    } else {
        byteArrayOf(0x15)
    }

internal fun resolveGoTerminalCompletion(commandLine: String): String? {
    val match = GO_COMMAND.findAll(commandLine).lastOrNull() ?: return null
    val commandStart = match.range.first + match.value.indexOf("go")
    val remainder = commandLine.substring(match.range.last + 1)
    val prefix = remainder.substringAfterLast(' ')
    if (prefix.isEmpty()) return null
    val showOptions = prefix.startsWith('-') && remainder.substringBeforeLast(' ', "").trim() == "test"
    val candidates = if (showOptions) GO_TEST_OPTIONS else GO_SUBCOMMANDS
    val completion = candidates.firstOrNull { it.startsWith(prefix) } ?: return null
    if (completion == prefix) return null
    return commandLine.substring(commandStart).removeSuffix(prefix) + completion
}

private val GO_COMMAND = Regex("(?:^|\\s)go\\s+")
private val GO_SUBCOMMANDS = listOf(
    "test", "telemetry", "tool", "bug", "build", "clean", "doc", "env", "fix", "fmt",
    "generate", "get", "help", "install", "list", "mod", "run", "version", "vet", "work",
)
private val GO_TEST_OPTIONS = listOf(
    "-run", "-bench", "-count", "-race", "-cover", "-coverprofile", "-covermode",
    "-coverpkg", "-json", "-v", "-short", "-failfast", "-timeout", "-cpu", "-parallel",
    "-shuffle", "-skip", "-list", "-tags", "-mod", "-modfile", "-gcflags", "-ldflags",
    "-trimpath", "-args", "-c", "-exec", "-o",
)
