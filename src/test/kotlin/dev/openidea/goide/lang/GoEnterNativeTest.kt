package dev.openidea.goide.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.settings.GoProjectSettings
import java.nio.file.Files

/** Real EditorActionManager/TypedAction, default plugin EPs, documents, carets and Undo. */
class GoEnterNativeTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testBlocksTypesAndEmptyPairsUseGoIndentInsteadOfCopyingThePreviousLine() {
        enter("package main\nfunc main() {<caret>}", "package main\nfunc main() {\n\t<caret>\n}")
        enter("package main\nfunc main(){\n\tif true {<caret>\n\t}\n}", "package main\nfunc main(){\n\tif true {\n\t\t<caret>\n\t}\n}")
        enter("package main\ntype Model struct {<caret>}", "package main\ntype Model struct {\n\t<caret>\n}")
        enter("package main\ntype Reader interface {<caret>}", "package main\ntype Reader interface {\n\t<caret>\n}")
        enter("package main\nfunc main(){ if true {<caret>} }", "package main\nfunc main(){ if true {\n\t\t<caret>\n\t} }")
    }
    fun testSwitchSelectCaseAndOrdinaryColonsHaveDifferentMeaning() {
        enter("package main\nfunc main(){\n\tswitch x {\n\tcase 1:<caret>\n\t}\n}", "package main\nfunc main(){\n\tswitch x {\n\tcase 1:\n\t\t<caret>\n\t}\n}")
        enter("package main\nfunc main(){\n\tselect {\n\tdefault:<caret>\n\t}\n}", "package main\nfunc main(){\n\tselect {\n\tdefault:\n\t\t<caret>\n\t}\n}")
        enter("package main\nfunc main(){\nlabel:<caret>\n}", "package main\nfunc main(){\nlabel:\n\t<caret>\n}")
        enter("package main\nvar x = map[string]int{\n\t\"key\":<caret>\n}", "package main\nvar x = map[string]int{\n\t\"key\":\n\t<caret>\n}")
        enter("package main\nfunc main(){\n\tswitch f(T{Key: \"case:\"}) {\n\tcase 1:<caret>\n\t}\n}", "package main\nfunc main(){\n\tswitch f(T{Key: \"case:\"}) {\n\tcase 1:\n\t\t<caret>\n\t}\n}")
    }
    fun testLabelsInSwitchAndSelectCasesUseTheBlockLabelIndentWithoutChangingMapKeysOrDeclarations() {
        enter("package main\nfunc f(){\n\tswitch {\n\tcase true:<caret>L: break L\n\t}\n}", "package main\nfunc f(){\n\tswitch {\n\tcase true:\n\t<caret>L: break L\n\t}\n}")
        enter("package main\nfunc f(){\n\tselect {\n\tdefault:<caret>L: break L\n\t}\n}", "package main\nfunc f(){\n\tselect {\n\tdefault:\n\t<caret>L: break L\n\t}\n}")
        enter("package main\nfunc f(){\n\tswitch {\n\tcase true:<caret>x := 1\n\t}\n}", "package main\nfunc f(){\n\tswitch {\n\tcase true:\n\t\t<caret>x := 1\n\t}\n}")
        enter("package main\nfunc f(){\n\tx := map[string]int{<caret>Key: 1,\n\t}\n}", "package main\nfunc f(){\n\tx := map[string]int{\n\t\t<caret>Key: 1,\n\t}\n}")
    }
    fun testDeepIncompleteDelimitersAndUnmatchedClosersVisitDocumentCharactersLinearly() {
        val options = GoIndentContext.Options(false, 4, 4, 4)
        for (depth in listOf(8_000, 16_000)) {
            for (suffix in listOf("", ")".repeat(depth), "]".repeat(depth) + " +")) {
                val source = "package p\nvar x=" + "F[".repeat(depth) + suffix + "\n"
                val counted = CountingText(source)
                assertEquals("    ", GoIndentContext(counted).lineIndent(source.length, options))
                assertTrue("Repeated source scans for depth $depth: ${counted.reads} reads / ${source.length} chars", counted.reads < source.length * 40L)
            }
        }
        // A sequence of balanced composites must not repeatedly walk all prior
        // pairs when classifying its next opening brace either.
        val counted = CountingText("package p\nvar x=" + "T{}+".repeat(8_000) + "T{\n")
        assertEquals("    ", GoIndentContext(counted).lineIndent(counted.length, options))
        assertTrue(counted.reads < counted.length * 40L)
    }
    fun testCompletedControlBlocksAndOrdinarySemicolonsDoNotLeakIntoTheNextCompositeLiteral() {
        for (statement in listOf("switch{};", "select{default:};", "var callback func(); _=callback;")) {
            enter("package main\nfunc f(){\n\t$statement _=T{<caret>Key:1,\n\t}\n}", "package main\nfunc f(){\n\t$statement _=T{\n\t\t<caret>Key:1,\n\t}\n}")
        }
        enter("package main\nfunc f(x any){\n\tswitch x.(type) {\n\tcase func(): _=T{<caret>Key:1,\n\t\t}\n\t}\n}", "package main\nfunc f(x any){\n\tswitch x.(type) {\n\tcase func(): _=T{\n\t\t\t<caret>Key:1,\n\t\t}\n\t}\n}")
    }
    fun testControlInitializersKeepTheirBodyAcrossSemicolonsAndFunctionLiterals() {
        for (statement in listOf("for i:=0;i<1;i++", "if x:=1;x>0", "for i:=func()int{return 0}();i<1;i++")) {
            enter("package main\nfunc f(){\n\t$statement {<caret>}\n}", "package main\nfunc f(){\n\t$statement {\n\t\t<caret>\n\t}\n}")
        }
        for (statement in listOf("switch x:=1;x", "switch x:=func()int{return 1};x()")) {
            enter("package main\nfunc f(){\n\t$statement {<caret>case 1:\n\t}\n}", "package main\nfunc f(){\n\t$statement {\n\t<caret>case 1:\n\t}\n}")
        }
    }
    fun testLabelledBlocksInsideCaseClausesRemainBlocksForTheirOwnLabels() {
        for ((control, clause) in listOf("switch" to "case true:", "select" to "default:")) {
            enter("package main\nfunc f(){\n\t$control {\n\t$clause\n\tL: {<caret>M:; if false {goto M}\n\t\t}\n\t}\n}", "package main\nfunc f(){\n\t$control {\n\t$clause\n\tL: {\n\t\t<caret>M:; if false {goto M}\n\t\t}\n\t}\n}")
        }
    }
    fun testEveryCompoundAssignmentOperatorContinuesFromItsStatementAnchor() {
        for (operator in listOf("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", "&^=")) {
            enter("package main\nfunc f(){\n\tx $operator<caret>\n}", "package main\nfunc f(){\n\tx $operator\n\t\t<caret>\n}")
        }
    }
    fun testFunctionElementTypesDoNotTurnCompositeKeysIntoBlockLabels() {
        for (type in listOf("map[string]func()", "[]func()", "[2]func()")) {
            enter("package main\nvar handlers = $type{<caret>Key: func(){},\n}", "package main\nvar handlers = $type{\n\t<caret>Key: func(){},\n}")
            enter("package main\nfunc f(){\n\tvar handlers $type = $type{<caret>Key: func(){},\n\t}\n}", "package main\nfunc f(){\n\tvar handlers $type = $type{\n\t\t<caret>Key: func(){},\n\t}\n}")
        }
        enter("package main\nvar model = struct{ Handler func() }{<caret>Handler: func(){},\n}", "package main\nvar model = struct{ Handler func() }{\n\t<caret>Handler: func(){},\n}")
        enter("package main\nvar model = struct{ Handlers []func() }{\n\tHandlers: []func(){<caret>Key: func(){},\n\t},\n}", "package main\nvar model = struct{ Handlers []func() }{\n\tHandlers: []func(){\n\t\t<caret>Key: func(){},\n\t},\n}")
    }
    fun testFunctionLiteralsReturningFunctionCollectionsAndControlInitializersKeepTheirBodies() {
        enter("package main\nvar f = func() map[string]func(){<caret>}", "package main\nvar f = func() map[string]func(){\n\t<caret>\n}")
        enter("package main\nvar f = func() []func(){<caret>}", "package main\nvar f = func() []func(){\n\t<caret>\n}")
        enter("package main\nfunc f(){\n\tif handlers:=map[string]func(){<caret>Key: func(){},\n\t}; handlers!=nil {}\n}", "package main\nfunc f(){\n\tif handlers:=map[string]func(){\n\t\t<caret>Key: func(){},\n\t}; handlers!=nil {}\n}")
        enter("package main\nfunc f(){\n\tif handlers:=map[string]func(){}; handlers!=nil {<caret>}\n}", "package main\nfunc f(){\n\tif handlers:=map[string]func(){}; handlers!=nil {\n\t\t<caret>\n\t}\n}")
        enter("package main\nfunc f(){\n\tswitch handlers:=map[string]func(){Key: func(){}}; len(handlers) {<caret>case 1:\n\t}\n}", "package main\nfunc f(){\n\tswitch handlers:=map[string]func(){Key: func(){}}; len(handlers) {\n\t<caret>case 1:\n\t}\n}")
    }
    fun testCallsGenericsCompositeLiteralsAndGroupedDeclarationsContinueFromTheirOwnAnchor() {
        enter("package main\nvar x = call(<caret>)", "package main\nvar x = call(\n\t<caret>\n)")
        enter("package main\nfunc F[<caret>](){}", "package main\nfunc F[\n\t<caret>\n](){}")
        enter("package main\nimport (<caret>)", "package main\nimport (\n\t<caret>\n)")
        enter("package main\nvar (<caret>)", "package main\nvar (\n\t<caret>\n)")
        enter("package main\nvar x = call(\n\tone,<caret>\n)", "package main\nvar x = call(\n\tone,\n\t<caret>\n)")
        enter("package main\nfunc main(){\n\tx := []int{\n\t\t1,<caret>\n\t}\n}", "package main\nfunc main(){\n\tx := []int{\n\t\t1,\n\t\t<caret>\n\t}\n}")
        enter("package main\nvar x = 1 +<caret>", "package main\nvar x = 1 +\n\t<caret>")
        enter("package main\nfunc main(){\n\tx := 1 +\n\t\t2 +<caret>\n}", "package main\nfunc main(){\n\tx := 1 +\n\t\t2 +\n\t\t<caret>\n}")
    }
    fun testClosingLinesAndCaseColonAlignWithTheirGoConstruct() {
        type("package main\nfunc main(){\n\tif true {\n\t\t<caret>\n}", '}', "package main\nfunc main(){\n\tif true {\n\t}<caret>\n}")
        type("package main\nvar x = call(\n\tone,\n\t\t<caret>", ')', "package main\nvar x = call(\n\tone,\n)<caret>")
        type("package main\nfunc main(){\n\tswitch x {\n\t\tcase 1<caret>\n\t}\n}", ':', "package main\nfunc main(){\n\tswitch x {\n\tcase 1:<caret>\n\t}\n}")
        type("package main\nfunc main(){\n\tname<caret>\n}", ':', "package main\nfunc main(){\n\tname:<caret>\n}") // ':' may still become :=.
    }
    fun testOvertypingAClosingBraceAlsoAlignsItsExistingLine() {
        type("package main\nfunc main(){\n\tif true {\n\t\t<caret>}\n}", '}', "package main\nfunc main(){\n\tif true {\n\t}<caret>\n}")
    }
    fun testSmartTabsUseTabsForBlockIndentAndSpacesForContinuationAlignment() {
        val manager = CodeStyleSettingsManager.getInstance(project)
        val settings = CodeStyle.getSettings(project).clone()
        settings.getLanguageIndentOptions(GoLanguage).apply { USE_TAB_CHARACTER = true; INDENT_SIZE = 4; TAB_SIZE = 4; CONTINUATION_INDENT_SIZE = 6; SMART_TABS = true }
        manager.setTemporarySettings(settings)
        try {
            enter("package main\nfunc main(){<caret>}", "package main\nfunc main(){\n\t<caret>\n}")
            enter("package main\nfunc main(){\n\tcall(<caret>)\n}", "package main\nfunc main(){\n\tcall(\n\t      <caret>\n\t)\n}")
        } finally { manager.dropTemporarySettings() }
    }
    fun testLiteralAndCommentDelimitersDoNotParticipateInGoIndentation() {
        enter("package main\nfunc main(){\n\tx := `raw { case:<caret>tail`\n}", "package main\nfunc main(){\n\tx := `raw { case:\n<caret>tail`\n}")
        enter("package main\nfunc main(){\n\tx := \"before<caret>after\"\n}", "package main\nfunc main(){\n\tx := \"before\n<caret>after\"\n}")
        enter("package main\nfunc main(){\n\t/* } case: */\n\tif true {<caret>\n\t}\n}", "package main\nfunc main(){\n\t/* } case: */\n\tif true {\n\t\t<caret>\n\t}\n}")
        enter("package main\nvar x = \"escaped\\\"<caret>", "package main\nvar x = \"escaped\\\"\n<caret>")
        enter("package main\nvar x = 'escaped\\'<caret>", "package main\nvar x = 'escaped\\'\n<caret>")
    }
    fun testRawStringIndentSettingPreservesWhitespaceAndRespectsSmartEnterWithUndo() {
        val settings = GoProjectSettings.get(project)
        val previous = settings.indentRawStringsOnEnter
        val smart = CodeInsightSettings.getInstance()
        val previousSmart = smart.SMART_INDENT_ON_ENTER
        try {
            settings.indentRawStringsOnEnter = false
            enter("package main\nvar x = `\n\t  中文😀{<caret>tail`", "package main\nvar x = `\n\t  中文😀{\n<caret>tail`")
            settings.indentRawStringsOnEnter = true
            smart.SMART_INDENT_ON_ENTER = true
            fixture("package main\nvar x = `\n\t  中文😀{<caret>tail`") { editor ->
                val before = editor.document.text
                performEnter(editor)
                assertEquals("package main\nvar x = `\n\t  中文😀{\n\t  <caret>tail`", marked(editor))
                val undo = UndoManager.getInstance(project)
                val wrapper = TextEditorProvider.getInstance().getTextEditor(editor)
                assertTrue(undo.isUndoAvailable(wrapper)); undo.undo(wrapper); assertEquals(before, editor.document.text)
            }
            smart.SMART_INDENT_ON_ENTER = false
            enter("package main\nvar x = `\n\t  content<caret>tail`", "package main\nvar x = `\n\t  content\n<caret>tail`")
        } finally { settings.indentRawStringsOnEnter = previous; smart.SMART_INDENT_ON_ENTER = previousSmart }
    }
    fun testUserIndentOptionsSmartEnterDisabledUnicodeAndCrLfAreRespected() {
        withOptions {
            enter("package main\nfunc main(){<caret>}", "package main\nfunc main(){\n  <caret>\n}")
            enter("package main\nvar x = call(<caret>)", "package main\nvar x = call(\n      <caret>\n)")
            enter("package main\nimport (<caret>)", "package main\nimport (\n  <caret>\n)")
        }
        val settings = CodeInsightSettings.getInstance(); val old = settings.SMART_INDENT_ON_ENTER
        try { settings.SMART_INDENT_ON_ENTER = false
            enter("package main\nfunc main(){<caret>}", "package main\nfunc main(){\n<caret>}")
        } finally { settings.SMART_INDENT_ON_ENTER = old }
        enter("package main\n// 中文😀\nfunc 名称(){<caret>}", "package main\n// 中文😀\nfunc 名称(){\n\t<caret>\n}")
        enter("package main\r\nfunc main(){<caret>}\r\n", "package main\nfunc main(){\n\t<caret>\n}\n")
    }
    fun testNativeEnterIsOneUndoableActionAndMultipleCaretsRetainTheirContexts() {
        fixture("package main\nfunc one(){<caret>}\nfunc two(){<caret>}") { editor ->
            val before = editor.document.text
            performEnter(editor)
            assertEquals("package main\nfunc one(){\n\t<caret>\n}\nfunc two(){\n\t<caret>\n}", marked(editor))
            val changed = editor.document.text
            val undo = UndoManager.getInstance(project); val wrapper = TextEditorProvider.getInstance().getTextEditor(editor)
            assertTrue(undo.isUndoAvailable(wrapper)); undo.undo(wrapper); assertEquals(before, editor.document.text)
            assertTrue(undo.isRedoAvailable(wrapper)); undo.redo(wrapper); assertEquals(changed, editor.document.text)
        }
    }

    private fun enter(before: String, after: String) = fixture(before) { editor -> performEnter(editor); assertEquals(after, marked(editor)) }
    private fun type(before: String, character: Char, after: String) = fixture(before) { editor ->
        TypedAction.getInstance().actionPerformed(editor, character, data(editor)); assertEquals(after, marked(editor))
    }
    private fun data(editor: Editor) = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
        .add(CommonDataKeys.PSI_FILE, PsiDocumentManager.getInstance(project).getPsiFile(editor.document)).build()
    private fun performEnter(editor: Editor) = WriteCommandAction.runWriteCommandAction(project, Runnable {
        EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER).execute(editor, null, data(editor))
    })
    private fun marked(editor: Editor): String {
        val out = StringBuilder(editor.document.text)
        editor.caretModel.allCarets.map { it.offset }.sortedDescending().forEach { out.insert(it, "<caret>") }
        return out.toString()
    }
    private fun withOptions(action: () -> Unit) {
        val manager = CodeStyleSettingsManager.getInstance(project)
        val settings = CodeStyle.getSettings(project).clone()
        settings.getLanguageIndentOptions(GoLanguage).apply { USE_TAB_CHARACTER = false; INDENT_SIZE = 2; TAB_SIZE = 8; CONTINUATION_INDENT_SIZE = 6 }
        manager.setTemporarySettings(settings)
        try { action() } finally { manager.dropTemporarySettings() }
    }
    private class CountingText(private val source: String) : CharSequence {
        var reads = 0L
        override val length get() = source.length
        override fun get(index: Int): Char { reads++; return source[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = source.subSequence(startIndex, endIndex)
    }
    private fun fixture(marked: String, action: (Editor) -> Unit) {
        val normalized = marked.replace("\r\n", "\n")
        val offsets = mutableListOf<Int>(); val source = StringBuilder()
        var i = 0
        while (i < normalized.length) { if (normalized.startsWith("<caret>", i)) { offsets += source.length; i += 7 } else source.append(normalized[i++]) }
        val root = Files.createTempDirectory("open-goland-enter-").toRealPath(); val disposable = Disposer.newDisposable("Go native enter fixture")
        VfsRootAccess.allowRootAccess(disposable, root.toString())
        try {
            val path = root.resolve("main.go"); Files.writeString(path, source.toString().let { if (marked.contains("\r\n")) it.replace("\n", "\r\n") else it })
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            val file = ReadAction.compute<com.intellij.psi.PsiFile, RuntimeException> { PsiManager.getInstance(project).findFile(virtual)!! }
            val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> { PsiDocumentManager.getInstance(project).getDocument(file)!! }
            EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run {
                val editor = EditorFactory.getInstance().createEditor(document, project, GoFileType.INSTANCE, false)
                try {
                    editor.caretModel.moveToOffset(offsets.first())
                    offsets.drop(1).forEach { offset -> editor.caretModel.addCaret(editor.offsetToVisualPosition(offset)) }
                    action(editor)
                } finally { EditorFactory.getInstance().releaseEditor(editor) }
            } }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }; Disposer.dispose(disposable) }
    }
}
