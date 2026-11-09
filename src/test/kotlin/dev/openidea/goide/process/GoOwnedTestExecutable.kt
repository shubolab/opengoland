package dev.openidea.goide.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import dev.openidea.goide.settings.GoToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Native owned subprocess fixtures, including Windows without a shell or interpreter. */
internal object GoOwnedTestExecutable {
    fun name(base: String) = base + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""

    fun compile(output: Path, source: String): Path {
        check(ApplicationManager.getApplication()?.isDispatchThread != true)
        check(!Files.exists(output)) { "A test program must not replace an existing executable." }
        val go = requireNotNull(GoToolchain.resolve("go",
            System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty())) { "A real Go SDK is required for native process controls." }
        val sdk = Path.of(go).toRealPath().parent.parent
        Files.createDirectories(output.parent)
        val sources = Files.createTempDirectory(output.parent, ".owned-go-source-")
        val file = Files.writeString(sources.resolve("main.go"), source)
        val command = GeneralCommandLine(go, "build", "-trimpath", "-buildvcs=false", "-o", output.toString(), file.toString())
            .withWorkDirectory(output.parent.toFile()).withEnvironment("GOROOT", sdk.toString())
            .withEnvironment("GOTOOLCHAIN", "local").withEnvironment("GOWORK", "off").withEnvironment("GOENV", "off")
            .withEnvironment("GOPROXY", "off").withEnvironment("GOSUMDB", "off").withEnvironment("GOTELEMETRY", "off")
            .withEnvironment("CGO_ENABLED", "0").withEnvironment("GOFLAGS", "").withEnvironment("GOEXPERIMENT", "")
            .withEnvironment("GOOS", "").withEnvironment("GOARCH", "").withEnvironment("GOTOOLDIR", "")
        val compiler = CapturingProcessHandler(command).apply { setShouldDestroyProcessRecursively(true) }
        try {
            val result = compiler.runProcess(TimeUnit.MINUTES.toMillis(2).toInt(), true)
            check(!result.isTimeout && !result.isCancelled && result.exitCode == 0) { "Native fixture compilation failed: ${result.stdout}${result.stderr}" }
            check(Files.isRegularFile(output) && Files.isExecutable(output))
            return output
        } finally {
            if (!compiler.isProcessTerminated) { compiler.destroyProcess(); check(compiler.waitFor(5_000)) }
            Files.walk(sources).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
