package dev.openidea.goide.terminal

import com.intellij.ide.DataManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.ui.popup.JBPopupFactory
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
        val dataContext = listOfNotNull(focusOwner, editor.contentComponent)
            .asSequence()
            .map(dataManager::getDataContext)
            .firstOrNull { TerminalView.DATA_KEY.getData(it) != null } ?: return
        val view = TerminalView.DATA_KEY.getData(dataContext) ?: return
        // When the bundled platform completion is active, preserve its richer
        // lookup instead of replacing it with the deterministic fallback.
        if (com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(editor) != null) return
        val outputModel = view.outputModels.active.value
        val cursorOffset = outputModel.cursorOffset
        val line = outputModel.getLineByOffset(cursorOffset)
        val lineStart = outputModel.getStartOfLine(line)
        val commandLine = outputModel.getText(lineStart, cursorOffset).toString()
        val completions = resolveGoTerminalCompletions(commandLine)
        when (completions.size) {
            0 -> return
            1 -> sendReplacement(view, completions.single())
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(completions)
                .setTitle("Go Command Completion")
                .setItemChosenCallback { command -> sendReplacement(view, command) }
                .createPopup()
                .showInBestPositionFor(dataContext)
        }
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

internal fun resolveGoTerminalCompletion(commandLine: String): String? =
    resolveGoTerminalCompletions(commandLine).firstOrNull()

internal fun resolveGoTerminalCompletions(commandLine: String): List<String> {
    val match = GO_COMMAND.findAll(commandLine).lastOrNull() ?: return emptyList()
    val commandStart = match.range.first + match.value.indexOf("go")
    val remainderStart = match.range.last + 1
    val remainder = commandLine.substring(remainderStart)
    val firstNonSpace = remainder.indexOfFirst { !it.isWhitespace() }
    if (firstNonSpace < 0) return emptyList()
    val arguments = remainder.substring(firstNonSpace).trimEnd().split(WHITESPACE)
    val prefix = arguments.last()
    val candidates = when {
        arguments.size == 1 -> GO_SUBCOMMANDS
        arguments.size == 2 && arguments[0] == "test" && prefix.startsWith('-') -> GO_TEST_OPTIONS
        else -> return emptyList()
    }
    val matches = candidates.filter { it.startsWith(prefix) && it != prefix }
    val prefixStart = remainderStart + remainder.lastIndexOf(prefix)
    val stem = commandLine.substring(commandStart, prefixStart)
    return matches.map { stem + it }
}

private val GO_COMMAND = Regex("(?:^|\\s)go\\s+")
private val WHITESPACE = Regex("\\s+")
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
