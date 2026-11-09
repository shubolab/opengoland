package com.redhat.devtools.lsp4ij

import com.intellij.codeInsight.TargetElementUtilBase
import com.intellij.codeInsight.navigation.GotoImplementationHandler
import com.intellij.codeInsight.navigation.GotoTargetHandler
import com.intellij.codeInsight.navigation.ImplementationSearcher
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition
import dev.openidea.goide.lang.GoLanguage
import dev.openidea.goide.lsp.GoLanguageServerFactory
import dev.openidea.goide.navigation.GoTargetElementEvaluator
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Standard Platform delegates -> actual owned stdio gopls; no direct LSP feature-support calls. */
class GoStandardNavigationGoplsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testStandardImplementationCollectsActualInterfaceMethodAndGenericAliasTargets() = fixture { f ->
        val reader = f.implementations("types.go", "type Reader", 7)
        assertEquals(setOf("Value", "Pointer"), read { reader.targets.map { it.text }.toSet() })
        assertEquals(2, reader.targets.size)
        assertFalse("The lexical origin must not appear as an implementation", reader.targets.any { it == reader.source })
        assertEquals("The standard handler initially discovers one target before its background chooser update", 1, reader.initialTargets.size)
        assertTrue(f.requests.all { it.method == "textDocument/implementation" })
        assertEquals("Definitions search uses the lexical query origin's identifier start, not the caret inside it",
            position(TYPES, TYPES.indexOf("type Reader") + 5), (f.requests.first().params as ImplementationParams).position)

        val methods = f.implementations("types.go", "Read() string", 2)
        assertEquals(setOf(TYPES.indexOf("func (Value) Read") + "func (Value) ".length,
            TYPES.indexOf("func (*Pointer) Read") + "func (*Pointer) ".length), read { methods.targets.map { it.textRange.startOffset }.toSet() })
        assertTrue(read { methods.targets.all { it.containingFile.virtualFile == f.files.getValue("types.go") } })

        val concrete = f.implementations("types.go", "type IntGetter", 7)
        assertEquals("A concrete alias has a different set from querying the generic Getter origin",
            setOf("Getter", "Box", "IntBox"), read { concrete.targets.map { it.text }.toSet() })
        assertFalse(read { concrete.targets.any { it.text == "StringBox" } })
        assertTrue("Each distinct standard query must reach actual gopls", f.requests.count { it.method == "textDocument/implementation" } >= 3)
    }

    fun testActual261TypeSymbolDelegateKeepsUtf16TypeTargetsDistinctFromCmdBDeclarationsAndSdkSources() = fixture { f ->
        val source = f.documents.getValue("source.go").text
        val variable = source.indexOf("current Reader")
        val query = variable + 3
        val target = f.typeTarget("source.go", query)
        assertEquals(f.files.getValue("types.go"), target.first)
        assertEquals(TYPES.indexOf("type Reader") + 5, target.second)
        assertEquals(listOf("textDocument/typeDefinition"), f.requests.map { it.method })
        assertEquals("Java/Kotlin offsets and the actual request must include both emoji UTF-16 units",
            position(source, query), (f.requests.single().params as TypeDefinitionParams).position)

        val use = source.indexOf("return current.Read") + "return ".length + 2
        var declarations = emptyArray<PsiElement>()
        edt { declarations = GotoDeclarationAction.findAllTargetElements(project, f.editor("source.go", use), use) }
        assertEquals(1, declarations.size)
        assertEquals(f.files.getValue("source.go"), read { declarations.single().containingFile.virtualFile })
        assertEquals(variable, read { declarations.single().textRange.startOffset })
        assertEquals("Cmd+B must remain a definition request, not type-definition navigation", "textDocument/definition", f.requests.last().method)

        val std = source.indexOf("std io.Reader") + 1
        val sdk = f.typeTarget("source.go", std)
        assertTrue("Standard type navigation must resolve actual selected-SDK source: ${sdk.first.path}", sdk.first.path.endsWith("/src/io/io.go"))
        assertEquals("Reader", read { PsiDocumentManager.getInstance(project).getDocument(PsiManager.getInstance(project).findFile(sdk.first)!!)!!.text.substring(sdk.second, sdk.second + 6) })
        assertEquals("textDocument/typeDefinition", f.requests.last().method)
    }

    fun testStandardTypeSymbolDelegateUsesCommittedUnsavedChangesWithoutWritingTheSource() = fixture { f ->
        val document = f.documents.getValue("source.go")
        val disk = Files.readString(f.root.resolve("source.go"))
        val old = "current Reader = Value{}"
        val changed = "current *Pointer = &Pointer{}"
        edt {
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                val start = document.text.indexOf(old)
                document.replaceString(start, start + old.length, changed)
            })
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        f.wrapper.flush()
        assertTrue("PSI commit is not Save", read { FileDocumentManager.getInstance().isDocumentUnsaved(document) })
        assertEquals(disk, Files.readString(f.root.resolve("source.go")))
        val offset = document.text.indexOf("current *Pointer") + 3
        val target = f.typeTarget("source.go", offset)
        assertEquals(f.files.getValue("types.go"), target.first)
        assertEquals(TYPES.indexOf("type Pointer") + 5, target.second)
        val params = f.requests.last().params as TypeDefinitionParams
        assertEquals(position(document.text, offset), params.position)
        val opened = f.opens.single { it.textDocument.uri == params.textDocument.uri }
        assertTrue("The real synchronizer must deliver an incremental didChange before querying",
            f.changes.any { it.textDocument.uri == params.textDocument.uri && it.textDocument.version > opened.textDocument.version && it.contentChanges.isNotEmpty() })
        assertTrue(read { FileDocumentManager.getInstance().isDocumentUnsaved(document) })
        assertEquals(disk, Files.readString(f.root.resolve("source.go")))
    }

    fun testTargetOnlyUnsavedEditRequeriesTheSameStandardTypeOriginInsteadOfReusingOldCoordinates() = fixture { f ->
        val source = f.documents.getValue("source.go")
        val target = f.documents.getValue("types.go")
        val sourceDisk = Files.readString(f.root.resolve("source.go"))
        val targetDisk = Files.readString(f.root.resolve("types.go"))
        edt {
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                val old = "current Reader = Value{}"
                val start = source.text.indexOf(old)
                source.replaceString(start, start + old.length, "current *Pointer = &Pointer{}")
                target.insertString(0, "// first unsaved 中文😀\n")
            })
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        f.wrapper.flush()
        val caret = source.text.indexOf("current *Pointer") + 3
        edt { f.editor("source.go", caret) }
        val first = f.typeTarget("source.go", caret)
        assertEquals(f.files.getValue("types.go"), first.first)
        assertEquals(target.text.indexOf("type Pointer") + 5, first.second)
        val firstRequests = f.requests.filter { it.method == "textDocument/typeDefinition" }
        assertEquals(1, firstRequests.size)
        val sourceStamp = source.modificationStamp
        val sourcePsiStamp = f.psi.getValue("source.go").modificationStamp

        edt {
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                target.insertString(0, "// second target-only unsaved 前😀\n")
            })
            PsiDocumentManager.getInstance(project).commitDocument(target)
        }
        f.wrapper.flush()
        assertEquals("Only the target document changed", sourceStamp, source.modificationStamp)
        assertEquals(sourcePsiStamp, f.psi.getValue("source.go").modificationStamp)
        assertEquals(caret, read { f.editors.getValue("source.go").caretModel.offset })
        val second = f.typeTarget("source.go", caret)
        val secondRequests = f.requests.filter { it.method == "textDocument/typeDefinition" }
        assertEquals("A moving target requires a new semantic response, not just a marker following old text", 2, secondRequests.size)
        assertNotSame(firstRequests.single(), secondRequests.last())
        assertEquals((firstRequests.single().params as TypeDefinitionParams).position,
            (secondRequests.last().params as TypeDefinitionParams).position)
        assertEquals(f.files.getValue("types.go"), second.first)
        assertEquals("Cached LSP line/column coordinates must not be reinterpreted against a changed target",
            target.text.indexOf("type Pointer") + 5, second.second)
        assertTrue(read { FileDocumentManager.getInstance().isDocumentUnsaved(source) })
        assertTrue(read { FileDocumentManager.getInstance().isDocumentUnsaved(target) })
        assertEquals(sourceDisk, Files.readString(f.root.resolve("source.go")))
        assertEquals(targetDisk, Files.readString(f.root.resolve("types.go")))
    }

    fun testAnotherUnsavedFileCanAddAnImplementationWithoutChangingTheOriginOrOldTargets() = fixture { f ->
        val declaration = f.documents.getValue("types.go")
        val untouchedStamp = declaration.modificationStamp
        val untouchedPsiStamp = f.psi.getValue("types.go").modificationStamp
        val first = f.implementations("types.go", "type Reader", 7)
        assertEquals(setOf("Value", "Pointer"), read { first.targets.map { it.text }.toSet() })
        val beforeRequests = f.requests.filter { it.method == "textDocument/implementation" }
        val other = f.documents.getValue("other.go")
        val otherDisk = Files.readString(f.root.resolve("other.go"))
        edt {
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                val old = "Read() int { return 0 }"
                val start = other.text.indexOf(old)
                other.replaceString(start, start + old.length, "Read() string { return \"remote\" }")
            })
            PsiDocumentManager.getInstance(project).commitDocument(other)
        }
        f.wrapper.flush()
        assertEquals("The query and every original destination remain unchanged", untouchedStamp, declaration.modificationStamp)
        assertEquals(untouchedPsiStamp, f.psi.getValue("types.go").modificationStamp)
        val second = f.implementations("types.go", "type Reader", 7)
        val afterRequests = f.requests.filter { it.method == "textDocument/implementation" }
        assertTrue("A new compatible type in a previously unmatched file must trigger a fresh semantic request", afterRequests.size > beforeRequests.size)
        assertEquals((beforeRequests.first().params as ImplementationParams).position,
            (afterRequests.last().params as ImplementationParams).position)
        assertEquals(setOf("Value", "Pointer", "Remote"), read { second.targets.map { it.text }.toSet() })
        assertEquals(f.files.getValue("other.go"), read { second.targets.single { it.text == "Remote" }.containingFile.virtualFile })
        assertTrue(read { FileDocumentManager.getInstance().isDocumentUnsaved(other) })
        assertEquals(otherDisk, Files.readString(f.root.resolve("other.go")))
    }

    private inner class Fixture(
        val root: Path, val files: Map<String, VirtualFile>, val psi: Map<String, PsiFile>,
        val documents: Map<String, Document>, val editors: Map<String, Editor>,
        val wrapper: OwnedWrapper, val requests: CopyOnWriteArrayList<RequestMessage>,
        val changes: CopyOnWriteArrayList<DidChangeTextDocumentParams>,
        val opens: CopyOnWriteArrayList<DidOpenTextDocumentParams>,
    ) {
        fun editor(name: String, offset: Int): Editor = editors.getValue(name).also { it.caretModel.moveToOffset(offset) }
        fun implementations(name: String, needle: String, advance: Int): ImplementationTargets {
            var result: GotoTargetHandler.GotoData? = null
            var targets = emptyArray<PsiElement>()
            edt {
                val offset = documents.getValue(name).text.indexOf(needle) + advance
                val editor = editor(name, offset)
                result = GotoImplementationHandler().getSourceAndTargetElements(editor, psi.getValue(name))
                val data = requireNotNull(result) { "The standard implementation action did not resolve a query origin" }
                // The actual action intentionally starts with FirstImplementationsSearcher.
                // Its popup later uses the full definitions-search path. Exercise
                // that standard collector separately; no popup/background-updater
                // completion or native chooser behavior is claimed by this test.
                targets = requireNotNull(ImplementationSearcher().searchImplementations(editor, data.source, offset))
            }
            val data = requireNotNull(result)
            return ImplementationTargets(data.source, targets, data.targets)
        }
        fun typeTarget(name: String, offset: Int): Pair<VirtualFile, Int> = read {
            val result = requireNotNull(typeResult(psi.getValue(name), offset)) { "The actual 261 type-symbol bridge returned no navigation" }
            assertEquals("SingleTarget", result.javaClass.simpleName)
            val requestor = result.javaClass.getMethod("getRequestor").invoke(result)
            val request = requireNotNull(Class.forName("com.intellij.codeInsight.navigation.impl.NavigationRequestor")
                .getMethod("navigationRequest").invoke(requestor))
            val file = request.javaClass.getMethod("getFile").invoke(request) as VirtualFile
            val offset = (request.javaClass.getMethod("getOffsetMarker").invoke(request) as RangeMarker).startOffset
            file to offset
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val executable = System.getenv("OPENGOLAND_GOPLS_TEST")?.let(Path::of)
            ?: System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { Path.of(it, "gopls") }.firstOrNull(Files::isExecutable)
        Assume.assumeTrue("Install gopls or set OPENGOLAND_GOPLS_TEST", executable != null)
        val root = Files.createTempDirectory("open-goland-standard-gopls-中文😀-").toRealPath()
        val disposable = Disposer.newDisposable("owned standard navigation gopls")
        // The owned gopls process inherits GOROOT (CI selects its authenticated
        // builder SDK), otherwise it uses Go from PATH. SDK-source navigation is
        // part of this test, so grant only that SDK in addition to the fixture.
        val sdk = System.getenv("GOROOT")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: requireNotNull(PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("go")).toPath().toRealPath().parent.parent
        VfsRootAccess.allowRootAccess(disposable, root.toString(), sdk.toRealPath().toString())
        val evaluatorExtension = TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR
        val explicit = System.getProperty("openidea.testNavigationEvaluator") == "true"
        val evaluator = GoTargetElementEvaluator()
        if (explicit) evaluatorExtension.addExplicitExtension(GoLanguage, evaluator)
        val accessor = LanguageServiceAccessor.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val started = LanguageServiceAccessor::class.java.getDeclaredField("startedServers").apply { isAccessible = true }.get(accessor) as MutableSet<LanguageServerWrapper>
        val previous = synchronized(started) { started.toList() }
        val definition = requireNotNull(LanguageServersRegistry.getInstance().getServerDefinition("openidea.gopls"))
        val wrapper = OwnedWrapper(project, definition)
        val pool = Executors.newCachedThreadPool()
        val requests = CopyOnWriteArrayList<RequestMessage>()
        val changes = CopyOnWriteArrayList<DidChangeTextDocumentParams>()
        val opens = CopyOnWriteArrayList<DidOpenTextDocumentParams>()
        val native = linkedMapOf<String, Editor>()
        val files = linkedMapOf<String, VirtualFile>()
        var process: Process? = null
        val descendants = ConcurrentHashMap<Long, ProcessHandle>()
        var watcher: Future<*>? = null
        var transport: Future<Void>? = null
        var remote: LanguageServer? = null
        try {
            Files.writeString(root.resolve("go.mod"), "module example.org/standard\n\ngo 1.24.0\n")
            for ((name, text) in mapOf("source.go" to SOURCE, "types.go" to TYPES, "other.go" to OTHER)) {
                val path = root.resolve(name); Files.writeString(path, text)
                files[name] = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            }
            val psi = files.mapValues { (_, file) -> read { requireNotNull(PsiManager.getInstance(project).findFile(file)) } }
            val documents = psi.mapValues { (_, file) -> read { requireNotNull(PsiDocumentManager.getInstance(project).getDocument(file)) } }
            edt { documents.forEach { (name, document) -> native[name] = EditorFactory.getInstance().createEditor(document, project) } }
            process = ProcessBuilder(executable.toString(), "serve").directory(root.toFile()).redirectError(root.resolve("gopls.stderr").toFile()).apply {
                environment()["GOTOOLCHAIN"] = "local"; environment()["GOTELEMETRY"] = "off"
                environment()["GOPROXY"] = "off"; environment()["GOWORK"] = "off"; environment()["CGO_ENABLED"] = "0"
            }.start()
            val owned = process
            watcher = pool.submit {
                try {
                    while (!Thread.currentThread().isInterrupted && owned.isAlive) {
                        owned.toHandle().descendants().use { children -> children.forEach { descendants.putIfAbsent(it.pid(), it) } }
                        Thread.sleep(20)
                    }
                } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            val launcher = wrapper.features.createLauncherBuilder<LanguageServer>().setRemoteInterface(LanguageServer::class.java)
                .setLocalService(Client()).setInput(process.inputStream).setOutput(process.outputStream).setExecutorService(pool)
                .wrapMessages { consumer -> MessageConsumer { message ->
                    if (message is RequestMessage && message.method.startsWith("textDocument/")) requests += message
                    if (message is NotificationMessage && message.method == "textDocument/didChange") changes += message.params as DidChangeTextDocumentParams
                    if (message is NotificationMessage && message.method == "textDocument/didOpen") opens += message.params as DidOpenTextDocumentParams
                    consumer.consume(message)
                } }.create()
            transport = launcher.startListening()
            val server = launcher.remoteProxy; remote = server
            val initialized = server.initialize(InitializeParams().apply {
                capabilities = ClientCapabilities().apply { general = GeneralClientCapabilities().apply { positionEncodings = listOf("utf-16") } }
                rootUri = root.toUri().toString(); workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "standard"))
                initializationOptions = mapOf("staticcheck" to false)
            }).get(30, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            wrapper.attach(server, initialized.capabilities, transport)
            for ((name, file) in files) wrapper.connect(file, read { wrapper.createFileConnectionInfo(file, documents.getValue(name), true) }).get(15, TimeUnit.SECONDS)
            synchronized(started) { started.clear(); started.add(wrapper) }
            block(Fixture(root, files, psi, documents, native, wrapper, requests, changes, opens))
        } finally {
            synchronized(started) { started.clear(); started.addAll(previous) }
            files.values.forEach { runCatching { wrapper.disconnect(it, false) } }; runCatching { wrapper.flush() }
            process?.toHandle()?.descendants()?.use { children -> children.forEach { descendants.putIfAbsent(it.pid(), it) } }
            runCatching { remote?.shutdown()?.get(5, TimeUnit.SECONDS) }; runCatching { remote?.exit() }
            process?.let { owned ->
                if (!owned.waitFor(5, TimeUnit.SECONDS)) { owned.destroy(); if (!owned.waitFor(2, TimeUnit.SECONDS)) { owned.destroyForcibly(); check(owned.waitFor(3, TimeUnit.SECONDS)) { "Owned gopls ${owned.pid()} did not terminate" } } }
                check(!owned.isAlive) { "Owned gopls is still alive" }
            }
            watcher?.cancel(true)
            // Only handles observed under this fixture's process are eligible;
            // never look up or terminate another session's Go/telemetry process.
            for (child in descendants.values) {
                if (child.isAlive) child.destroy()
                if (child.isAlive) runCatching { child.onExit().get(2, TimeUnit.SECONDS) }
                if (child.isAlive) { child.destroyForcibly(); runCatching { child.onExit().get(3, TimeUnit.SECONDS) } }
            }
            transport?.cancel(true)
            edt { native.values.forEach(EditorFactory.getInstance()::releaseEditor) }
            Disposer.dispose(wrapper)
            pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS)) { "Owned stdio threads did not stop" }
            if (explicit) evaluatorExtension.removeExplicitExtension(GoLanguage, evaluator)
            Disposer.dispose(disposable)
            root.toFile().deleteRecursively(); LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            val remaining = descendants.values.filter(ProcessHandle::isAlive).map(ProcessHandle::pid)
            println("STANDARD_NAVIGATION_CLEANUP goplsPid=${process?.pid()} descendantPids=${descendants.keys.sorted()} remaining=$remaining")
            check(remaining.isEmpty()) { "Owned descendants survived cleanup: $remaining" }
        }
    }

    private class OwnedWrapper(project: Project, definition: LanguageServerDefinition) : LanguageServerWrapper(project, definition) {
        val features: LSPClientFeatures = GoLanguageServerFactory().createClientFeatures().also { it.setServerWrapper(this) }
        private lateinit var remote: LanguageServer
        fun attach(server: LanguageServer, capabilities: ServerCapabilities, transport: Future<Void>) {
            remote = server
            setField("languageServer", server); setField("serverCapabilities", capabilities)
            setField("initializeFuture", CompletableFuture.completedFuture<Void>(null)); setField("launcherFuture", transport)
        }
        override fun start() {}
        override fun getInitializedServer(): CompletableFuture<LanguageServer> = CompletableFuture.completedFuture(remote)
        override fun getClientFeatures() = features
        override fun getServerStatus() = ServerStatus.started
        override fun isEnabled() = true
        override fun canOperate(file: VirtualFile) = true
        fun flush() { sendNotification { it }.get(5, TimeUnit.SECONDS) }
        override fun dispose() {
            openedDocuments.values.forEach { it.synchronizer?.let(Disposer::dispose) }
            openedDocuments.clear(); stopDispatcher(); features.dispose()
        }
        private fun setField(name: String, value: Any) { LanguageServerWrapper::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value) }
    }

    private class Client : LanguageClient {
        override fun telemetryEvent(value: Any?) {}
        override fun publishDiagnostics(params: PublishDiagnosticsParams) {}
        override fun showMessage(params: MessageParams) {}
        override fun logMessage(params: MessageParams) {}
        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> = CompletableFuture.completedFuture(null)
        override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> = CompletableFuture.completedFuture(params.items.map { emptyMap<String, Any>() })
    }

    private fun typeResult(file: PsiFile, offset: Int): Any? {
        val data = Class.forName("com.intellij.codeInsight.navigation.impl.GttdKt")
            .getMethod("gotoTypeDeclaration", PsiFile::class.java, Int::class.javaPrimitiveType).invoke(null, file, offset) ?: return null
        return data.javaClass.getMethod("result").invoke(data)
    }
    private data class ImplementationTargets(val source: PsiElement, val targets: Array<PsiElement>, val initialTargets: Array<PsiElement>)
    private fun <T> read(action: () -> T): T = ReadAction.compute<T, RuntimeException> { action() }
    private fun edt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { WriteIntentReadAction.run(Runnable(action)) }

    companion object {
        private const val TYPES = "package fixture\ntype Reader interface { Read() string }\ntype Value struct{}\nfunc (Value) Read() string { return \"value\" }\ntype Pointer struct{}\nfunc (*Pointer) Read() string { return \"pointer\" }\ntype Wrong struct{}\nfunc (Wrong) Read() int { return 0 }\ntype Getter[T any] interface { Get() T }\ntype IntGetter = Getter[int]\ntype Box[T any] struct { Value T }\nfunc (b Box[T]) Get() T { return b.Value }\ntype IntBox struct{}\nfunc (IntBox) Get() int { return 1 }\ntype StringBox struct{}\nfunc (StringBox) Get() string { return \"other\" }\n"
        private const val SOURCE = "package fixture\nimport (\"io\"; \"strings\")\nfunc Use() string { _ = \"前😀\"; var  current Reader = Value{}; return current.Read() }\nfunc Standard() int { var std io.Reader = strings.NewReader(\"stdlib\"); n,_:=std.Read(make([]byte,8)); return n }\n"
        private const val OTHER = "package fixture\ntype Remote struct{}\nfunc (Remote) Read() int { return 0 }\n"
        private fun position(text: String, offset: Int): Position { val prefix = text.substring(0, offset); return Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length) }
    }
}
