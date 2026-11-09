package dev.openidea.goide.lang

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.lang.LanguageQuoteHandling
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformCodeInsightTestCase
import com.redhat.devtools.lsp4ij.LanguageServersRegistry

/** Exercises the actual platform typed, Enter, Backspace and Undo action chains. */
class GoQuoteCommentNativeTest : LightPlatformCodeInsightTestCase() {
    override fun runInDispatchThread() = true
    override fun isRunInCommand() = false

    override fun setUp() {
        super.setUp()
        // These lexical editing tests require no server or external process.
        LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls")?.let { definition ->
            val enabled = definition.isEnabled(project)
            definition.setEnabled(false, project)
            Disposer.register(testRootDisposable) { definition.setEnabled(enabled, project) }
        }
        if (LanguageQuoteHandling.INSTANCE.forLanguage(GoLanguage) !is GoQuoteHandler) {
            val quote = GoQuoteHandler()
            LanguageQuoteHandling.INSTANCE.addExplicitExtension(GoLanguage, quote)
            Disposer.register(testRootDisposable) { LanguageQuoteHandling.INSTANCE.removeExplicitExtension(GoLanguage, quote) }
        }
        if (TypedHandlerDelegate.EP_NAME.extensionList.none { it is GoQuoteTypedHandler }) {
            TypedHandlerDelegate.EP_NAME.point.registerExtension(GoQuoteTypedHandler(), testRootDisposable)
        }
        if (EnterHandlerDelegate.EP_NAME.extensionList.none { it is GoCommentEnterHandler }) {
            EnterHandlerDelegate.EP_NAME.point.registerExtension(GoCommentEnterHandler(), testRootDisposable)
        }
    }

    fun testAllThreeLiteralKindsPairOvertypeAndDeleteAnEmptyPair() {
        for (quote in listOf('"', '\'', '`')) {
            configure("package main\nvar value = <caret>")
            type(quote)
            check("package main\nvar value = $quote<caret>$quote")
            type(quote)
            check("package main\nvar value = $quote$quote<caret>")
            configure("package main\nvar value = $quote<caret>$quote")
            backspace()
            check("package main\nvar value = <caret>")
        }
    }

    fun testLiteralContentsEscapesAndDifferentQuoteKindsAreNotPaired() {
        configure("package main\nvar value = \"a<caret>b\"")
        type('\'')
        check("package main\nvar value = \"a'<caret>b\"")
        configure("package main\nvar value = `a<caret>b`")
        type('"')
        check("package main\nvar value = `a\"<caret>b`")
        configure("package main\nvar value = \"\\<caret>\"")
        type('"')
        check("package main\nvar value = \"\\\"<caret>\"")
        configure("package main\nvar value = \"\\\\<caret>\"")
        type('"')
        check("package main\nvar value = \"\\\\\"<caret>")
        configure("package main\nvar value = '\\<caret>'")
        type('\'')
        check("package main\nvar value = '\\'<caret>'")
    }

    fun testRawStringsDoNotTreatBackslashesAsEscapesAndPreserveNewlines() {
        configure("package main\nvar value = `line\npath\\<caret>`")
        type('`')
        check("package main\nvar value = `line\npath\\`<caret>")
        configure("package main\nvar value = `line\\<caret>tail`")
        type('`')
        check("package main\nvar value = `line\\`<caret>tail`")
        configure("package main\nvar value = `line<caret>tail`")
        enter()
        check("package main\nvar value = `line\n<caret>tail`")
    }

    fun testCommentsNeverAutoPairOrOvertypeAQuote() {
        for (comment in listOf("// \"<caret>", "/* '<caret> */", "// `<caret>`")) {
            configure("package main\n$comment")
            type('"')
            check("package main\n" + comment.replace("<caret>", "\"<caret>"))
        }
        configure("package main\n// \"<caret>\"")
        backspace()
        check("package main\n// <caret>\"")
    }

    fun testBackspaceKeepsNonemptyAndEscapedLiteralContentsIntact() {
        for (quote in listOf('"', '\'', '`')) {
            configure("package main\nvar value = ${quote}a<caret>$quote")
            backspace()
            check("package main\nvar value = $quote<caret>$quote")
            configure("package main\nvar value = ${quote}a$quote<caret>")
            backspace()
            check("package main\nvar value = ${quote}a<caret>")
        }
        configure("package main\nvar value = \"\\\"<caret>\"")
        backspace()
        check("package main\nvar value = \"\\<caret>\"")
    }

    fun testRecoveryAfterInvalidLiteralAndSupplementaryIdentifierBoundaries() {
        configure("package main\nvar broken = \"bad\nvar next = <caret>")
        type('"')
        check("package main\nvar broken = \"bad\nvar next = \"<caret>\"")
        for (quote in listOf('"', '\'', '`')) {
            for (identifier in listOf("next", "世界", "𐐀name")) {
                configure("package main\nvar value = <caret>$identifier")
                type(quote)
                check("package main\nvar value = $quote<caret>$identifier")
            }
        }
    }

    fun testSmartQuoteAndSelectionSettingsRemainPlatformControlled() {
        CodeInsightSettings.runWithTemporarySettings<Unit, RuntimeException> { settings ->
            settings.AUTOINSERT_PAIR_QUOTE = false
            configure("package main\nvar value = <caret>")
            type('"')
            check("package main\nvar value = \"<caret>")
            configure("package main\nvar value = `path\\<caret>`")
            type('`')
            check("package main\nvar value = `path\\`<caret>`")
        }
        CodeInsightSettings.runWithTemporarySettings<Unit, RuntimeException> { settings ->
            settings.SURROUND_SELECTION_ON_QUOTE_TYPED = true
            configure("package main\nvar value = <selection>hello</selection><caret>")
            type('"')
            checkResultByText("package main\nvar value = \"<selection>hello</selection><caret>\"")
        }
        CodeInsightSettings.runWithTemporarySettings<Unit, RuntimeException> { settings ->
            settings.SURROUND_SELECTION_ON_QUOTE_TYPED = false
            configure("package main\nvar value = <selection>hello</selection><caret>")
            type('"')
            check("package main\nvar value = \"<caret>\"")
        }
    }

    fun testLineCommentSplitsRetainSpacingButEndOfLineAndDirectivesExit() {
        configure("package main\n\t//  hello<caret> world")
        enter()
        check("package main\n\t//  hello\n\t//  <caret>world")
        configure("package main\nvar x = 1 // hello<caret> world")
        enter()
        check("package main\nvar x = 1 // hello\n// <caret>world")
        configure("package main\n// hello<caret>// world")
        enter()
        check("package main\n// hello\n<caret>// world")
        for (comment in listOf("// hello<caret>", "//<caret>")) {
            configure("package main\n$comment")
            enter()
            check("package main\n" + comment.replace("<caret>", "\n<caret>"))
        }
        for (directive in listOf("//go:build linux", "//line foo.go:1", "// +build linux", "//\t +build linux", "//   +build linux", "//export Symbol")) {
            configure("package main\n$directive<caret> more")
            enter()
            // The split leaves comment syntax; normal Go indentation applies.
            check("package main\n$directive\n<caret>more")
        }
    }

    fun testBlockCommentsCloseOnceAndPreservePlainAndStarredStyles() {
        configure("package main\n\t/*<caret>*/")
        enter()
        check("package main\n\t/*\n\t <caret>\n\t */")
        configure("package main\n/**<caret>*/")
        enter()
        check("package main\n/**\n * <caret>\n */")
        configure("package main\n/* hello<caret> world */")
        enter()
        check("package main\n/* hello\n <caret>world */")
        configure("package main\n/*\n * hello<caret> world\n */")
        enter()
        check("package main\n/*\n * hello\n * <caret>world\n */")
        configure("package main\n/* unfinished<caret>")
        enter()
        check("package main\n/* unfinished\n <caret>\n */")
        CodeInsightSettings.runWithTemporarySettings<Unit, RuntimeException> { settings ->
            settings.CLOSE_COMMENT_ON_ENTER = false
            configure("package main\n/* unfinished<caret>")
            enter()
            check("package main\n/* unfinished\n <caret>")
        }
        CodeInsightSettings.runWithTemporarySettings<Unit, RuntimeException> { settings ->
            settings.SMART_INDENT_ON_ENTER = false
            settings.CLOSE_COMMENT_ON_ENTER = true
            configure("package main\n/*<caret>")
            enter()
            check("package main\n/*\n <caret>\n */")
        }
        configure("package main\n/* done */<caret>")
        enter()
        check("package main\n/* done */\n<caret>")
    }

    fun testUtf16AndSingleCommandUndoRedoForTypingAndCommentContinuation() {
        val before = "package main\n// 😀\nvar 世界 = <caret>"
        configure(before)
        type('"')
        check("package main\n// 😀\nvar 世界 = \"<caret>\"")
        executeAction(IdeActions.ACTION_UNDO)
        check(before)
        executeAction(IdeActions.ACTION_REDO)
        check("package main\n// 😀\nvar 世界 = \"<caret>\"")
        val comment = "package main\n// 😀 hello<caret> 世界"
        configure(comment)
        enter()
        check("package main\n// 😀 hello\n// <caret>世界")
        executeAction(IdeActions.ACTION_UNDO)
        check(comment)
        executeAction(IdeActions.ACTION_REDO)
        check("package main\n// 😀 hello\n// <caret>世界")
    }

    private fun configure(text: String) { configureFromFileText("typing.go", text) }
    private fun check(text: String) { checkResultByText(text) }
    private fun enter() { executeAction(IdeActions.ACTION_EDITOR_ENTER) }
}
