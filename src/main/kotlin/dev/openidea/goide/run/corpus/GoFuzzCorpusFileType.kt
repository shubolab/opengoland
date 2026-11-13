package dev.openidea.goide.run.corpus

import com.intellij.lang.Language
import com.intellij.lexer.DelegateLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLikeFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.ByteSequence
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import dev.openidea.goide.icons.GoIcons
import dev.openidea.goide.lang.GoLexer
import dev.openidea.goide.lang.GoParserDefinition
import dev.openidea.goide.lang.GoTokens
import dev.openidea.goide.run.GoSourceFunctions
import dev.openidea.goide.run.GoTestRerunPlan
import dev.openidea.goide.semantic.GoIdentifiers
import java.nio.file.Path

/** Corpus values use Go literals, but are data files and must never attach to gopls. */
object GoFuzzCorpusLanguage : Language("OpenIDEAGoFuzzCorpus") {
    override fun getDisplayName() = "Go Fuzz Corpus"
}

class GoFuzzCorpusFileType private constructor() : LanguageFileType(GoFuzzCorpusLanguage), PlainTextLikeFileType {
    override fun getName() = "open Goland Fuzz Corpus"
    override fun getDescription() = "Go fuzz seed or failing corpus input"
    override fun getDefaultExtension() = ""
    override fun getIcon() = GoIcons.Corpus

    companion object { @JvmField val INSTANCE = GoFuzzCorpusFileType() }
}

/** PSI leaves must belong to the corpus language, which owns their native execution actions. */
private object GoFuzzCorpusTokens {
    private val mapped = listOf(GoTokens.KEYWORD, GoTokens.IDENTIFIER, GoTokens.NUMBER,
        GoTokens.STRING, GoTokens.COMMENT, GoTokens.OPERATOR, GoTokens.LBRACE, GoTokens.RBRACE,
        GoTokens.LPAREN, GoTokens.RPAREN, GoTokens.LBRACKET, GoTokens.RBRACKET)
        .associateWith { IElementType("GO_FUZZ_CORPUS_$it", GoFuzzCorpusLanguage) }

    fun fromGo(type: IElementType?): IElementType? = mapped[type] ?: type
    val comments = TokenSet.create(mapped.getValue(GoTokens.COMMENT))
    val strings = TokenSet.create(mapped.getValue(GoTokens.STRING))
}

private class GoFuzzCorpusLexer : DelegateLexer(GoLexer()) {
    override fun getTokenType(): IElementType? = GoFuzzCorpusTokens.fromGo(super.getTokenType())
}

class GoFuzzCorpusParserDefinition : GoParserDefinition(GoFuzzCorpusLanguage) {
    override fun createLexer(project: Project?): Lexer = GoFuzzCorpusLexer()
    override fun getCommentTokens() = GoFuzzCorpusTokens.comments
    override fun getStringLiteralElements() = GoFuzzCorpusTokens.strings
}

/** Detection consumes the platform-supplied prefix and cached path; it performs no file reads. */
class GoFuzzCorpusFileTypeDetector : FileTypeRegistry.FileTypeDetector {
    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?): FileType? {
        if (file.isDirectory || GoFuzzCorpusPath.fromPath(file.path) == null) return null
        val prefix = firstCharsIfText ?: buildString {
            for (index in 0 until minOf(firstBytes.length(), getDesiredContentPrefixLength())) {
                val byte = firstBytes.byteAt(index).toInt() and 0xff
                if (byte > 127) return null
                append(byte.toChar())
                if (byte == '\n'.code) break
            }
        }
        return GoFuzzCorpusPath.hasHeader(prefix).takeIf { it }?.let { GoFuzzCorpusFileType.INSTANCE }
    }

    override fun getDesiredContentPrefixLength() = 32
    override fun getVersion() = 1
}

data class GoFuzzCorpusPath(val path: Path, val packageDirectory: Path, val fuzzFunction: String, val caseName: String) {
    // Go rewrites whitespace/nonprintable test names and disambiguates collisions.
    // These identity names cannot collide with rewritten names or F.Add's seed#N.
    val canReplayExactly: Boolean get() = caseName.matches(IDENTITY_NAME) && caseName !in setOf(".", "..")
    /** Plain -run executes exactly this seed through gotest/Delve; it never starts mutation fuzzing. */
    val testPattern: String get() {
        require(canReplayExactly) { "This corpus filename needs Go test-name disambiguation before it can be replayed exactly." }
        return "^${GoTestRerunPlan.quote(fuzzFunction)}$/^${GoTestRerunPlan.quote(caseName)}$"
    }

    companion object {
        const val HEADER = "go test fuzz v1"
        private val IDENTITY_NAME = Regex("[A-Za-z0-9.-]+")

        fun hasHeader(text: CharSequence): Boolean {
            if (text.length <= HEADER.length || !text.startsWith(HEADER)) return false
            return text[HEADER.length] == '\n' || text.length > HEADER.length + 1 &&
                text[HEADER.length] == '\r' && text[HEADER.length + 1] == '\n'
        }

        fun fromPath(raw: String): GoFuzzCorpusPath? = runCatching { fromPath(Path.of(raw)) }.getOrNull()

        fun fromPath(path: Path): GoFuzzCorpusPath? {
            if (!path.isAbsolute || path.any { it.toString() in setOf(".", "..") }) return null
            val normalized = path.normalize()
            val case = normalized.fileName?.toString()?.takeIf { it.isNotEmpty() && it.none { c -> c.isISOControl() || c == '/' || c == '\\' } } ?: return null
            val functionDirectory = normalized.parent ?: return null
            val fuzz = functionDirectory.fileName?.toString() ?: return null
            if (!GoIdentifiers.valid(fuzz) || !GoSourceFunctions.isFuzzName(fuzz)) return null
            val fuzzDirectory = functionDirectory.parent?.takeIf { it.fileName?.toString() == "fuzz" } ?: return null
            val testdata = fuzzDirectory.parent?.takeIf { it.fileName?.toString() == "testdata" } ?: return null
            val pkg = testdata.parent ?: return null
            return GoFuzzCorpusPath(normalized, pkg, fuzz, case)
        }

        fun reportedPath(packageDirectory: String?, raw: String): GoFuzzCorpusPath? {
            val pkg = packageDirectory?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }?.normalize() ?: return null
            val reported = runCatching { Path.of(raw) }.getOrNull() ?: return null
            if (reported.any { it.toString() in setOf(".", "..") }) return null
            val candidate = fromPath(if (reported.isAbsolute) reported else pkg.resolve(reported)) ?: return null
            return candidate.takeIf { it.packageDirectory == pkg }
        }
    }
}
