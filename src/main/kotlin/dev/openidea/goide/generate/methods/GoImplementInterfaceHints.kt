package dev.openidea.goide.generate.methods

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.semantic.GoIdentifiers
import java.util.concurrent.atomic.AtomicLong

/** A lexical entry point only. The existing typed planner determines what can actually be implemented. */
class GoImplementInterfaceHints : InlayHintsProvider, DumbAware {
    override fun createCollector(file: PsiFile, editor: Editor): OwnBypassCollector? {
        if (file.language != GoLanguage || editor.isDisposed || editor.project !== file.project || file.project.isDisposed) return null
        return object : OwnBypassCollector {
            override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
                if (!file.isValid || editor.isDisposed || file.project.isDisposed) return
                val document = editor.document
                if (PsiDocumentManager.getInstance(file.project).getCachedPsiFile(document) !== file) return
                val stamp = document.modificationStamp
                val headers = GoInterfaceHeaders.scan(document.immutableCharSequence)
                if (stamp != document.modificationStamp) return
                val virtual = file.virtualFile
                val clickable = virtual != null && virtual.isValid && virtual.isInLocalFileSystem && virtual.isWritable && !editor.isViewer
                for (header in headers) {
                    val action = if (clickable) InlayActionData(StringInlayActionPayload(JsonObject().apply {
                        addProperty("document", documentIdentity(document)); addProperty("stamp", stamp)
                        addProperty("file", virtual!!.url); addProperty("offset", header.nameOffset); addProperty("name", header.name)
                    }.toString()), ACTION_ID) else null
                    sink.addPresentation(InlineInlayPosition(header.headerEnd, true), tooltip = "Choose a type and preview its interface methods.",
                        hintFormat = HintFormat.default.withColorKind(HintColorKind.TextWithoutBackground)) {
                        text("Implement interface", action)
                    }
                }
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "opengoland.implement.interface"
        const val ACTION_ID = "opengoland.implement.interface.click"
    }
}

/** Clicking never resolves files or starts a process; the service queues its analysis on a worker. */
class GoImplementInterfaceInlayAction internal constructor(private val invoke: (PsiFile, Editor) -> Unit) : InlayActionHandler {
    constructor() : this({ file, editor -> GoImplementMethodsService.get(file.project).invoke(file, editor) })

    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        if (!ApplicationManager.getApplication().isDispatchThread || e.editor.isDisposed) return
        val editor = e.editor
        val project = editor.project?.takeUnless { it.isDisposed } ?: return
        val encoded = (payload as? StringInlayActionPayload)?.text?.takeIf { it.length <= 8192 } ?: return
        val value = runCatching { JsonParser.parseString(encoded).asJsonObject }.getOrNull() ?: return
        val document = editor.document
        val target = runCatching {
            ReadAction.compute<Triple<PsiFile, Int, Int>?, RuntimeException> {
                if (value.get("document").asLong != document.getUserData(DOCUMENT_ID) || value.get("stamp").asLong != document.modificationStamp) return@compute null
                val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(document) ?: return@compute null
                val virtual = file.virtualFile ?: return@compute null
                if (!file.isValid || file.project !== project || file.language != GoLanguage || !virtual.isValid || !virtual.isInLocalFileSystem ||
                    !virtual.isWritable || editor.isViewer || virtual.url != value.get("file").asString) return@compute null
                val offset = value.get("offset").asInt
                val name = value.get("name").asString
                val text = document.immutableCharSequence
                if (!GoIdentifiers.valid(name) || offset < 0 || offset > text.length - name.length ||
                    text.subSequence(offset, offset + name.length).toString() != name || GoIdentifiers.wordAt(text, offset) != name) return@compute null
                Triple(file, offset, name.length)
            }
        }.getOrNull() ?: return
        editor.caretModel.moveToOffset(target.second)
        editor.selectionModel.setSelection(target.second, target.second + target.third)
        invoke(target.first, editor)
    }
}

private val DOCUMENT_ID = Key.create<Long>("opengoland.interface.inlay.document")
private val nextDocumentId = AtomicLong()
private fun documentIdentity(document: Document): Long = synchronized(document) {
    document.getUserData(DOCUMENT_ID) ?: nextDocumentId.incrementAndGet().also { document.putUserData(DOCUMENT_ID, it) }
}

internal data class GoInterfaceHeader(val name: String, val nameOffset: Int, val headerEnd: Int)

/** Tokens and offsets remain UTF-16; comments and quoted braces never affect structural balance. */
internal object GoInterfaceHeaders {
    fun scan(source: CharSequence): List<GoInterfaceHeader> =
        dev.openidea.goide.generate.GoNamedTypeHeaders.scan(source, "interface").map {
            GoInterfaceHeader(it.name, it.nameOffset, it.headerEnd)
        }
}
