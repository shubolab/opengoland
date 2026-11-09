package dev.openidea.goide.project

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities

data class GoModuleRequest(val modulePath: String, val directory: Path, val goExecutable: String, val createExample: Boolean = true) {
    fun validate() {
        require(modulePath.isNotBlank() && !modulePath.startsWith('-') && modulePath.none { it.isWhitespace() || it == '\u0000' }) { "Enter a module import path without command flags or whitespace, for example example.com/hello." }
        require(directory.isAbsolute && directory.normalize().parent != null) { "Choose an absolute project directory below a parent folder." }
        require(goExecutable.isNotBlank() && '\u0000' !in goExecutable) { "Choose a Go executable." }
    }
}

/** Generates in an owned staging directory, then reserves a new destination without replacing anything. */
object GoModuleGenerator {
    internal data class Generated(val directory: Path, val sdk: GoModuleSdk)

    fun generate(request: GoModuleRequest, cancelled: () -> Boolean = { false }, onOutput: (String) -> Unit = {}): Path =
        generateVerified(request, cancelled, onOutput).directory

    internal fun generateVerified(request: GoModuleRequest, cancelled: () -> Boolean = { false }, onOutput: (String) -> Unit = {}): Generated {
        check(!SwingUtilities.isEventDispatchThread()) { "Go module creation must run in a background task." }
        request.validate()
        checkCancelled(cancelled)
        val destination = request.directory.normalize()
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw IOException("Project directory already exists: $destination")
        val sdk = GoSdkProbe.inspect(request.goExecutable, cancelled, onOutput)
        checkCancelled(cancelled)
        val parent = destination.parent
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, ".openidea-go-module-")
        val moved = mutableListOf<Path>()
        var reserved = false
        var completed = false
        try {
            GoModuleProcesses.run(sdk.executable.toString(), listOf("mod", "init", "--", request.modulePath), staging, cancelled, onOutput = onOutput)
            checkCancelled(cancelled)
            if (request.createExample) {
                Files.writeString(staging.resolve("main.go"), MAIN, StandardCharsets.UTF_8)
                Files.writeString(staging.resolve("greeting.go"), GREETING, StandardCharsets.UTF_8)
                Files.writeString(staging.resolve("greeting_test.go"), TEST, StandardCharsets.UTF_8)
            }
            // Parsing with the verified SDK establishes both Go module grammar
            // and the requested module directive before a destination is reserved.
            GoSdkProbe.validateModule(sdk, staging, request.modulePath, cancelled)
            checkCancelled(cancelled)
            // createDirectory is exclusive, including when another process creates the path during go mod init.
            Files.createDirectory(destination)
            reserved = true
            Files.list(staging).use { files ->
                files.forEach { source ->
                    checkCancelled(cancelled)
                    val target = destination.resolve(source.fileName)
                    Files.move(source, target)
                    moved.add(target)
                }
            }
            completed = true
            return Generated(destination, sdk)
        } finally {
            deleteOwnedTree(staging)
            if (reserved && !completed) {
                moved.asReversed().forEach { path -> runCatching { Files.deleteIfExists(path) } }
                // Preserve any unexpected files rather than recursively deleting the destination.
                runCatching { Files.deleteIfExists(destination) }
            }
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Go project creation was cancelled.")
    }

    private fun deleteOwnedTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private const val MAIN = """package main

import (
	"flag"
	"fmt"
)

func main() {
	name := flag.String("name", "world", "name to greet")
	flag.Parse()
	fmt.Println(Greeting(*name))
}
"""
    private const val GREETING = """package main

func Greeting(name string) string {
	return "Hello, " + name + "!"
}
"""
    private const val TEST = """package main

import "testing"

func TestGreeting(t *testing.T) {
	for _, name := range []string{"world", "Go"} {
		t.Run(name, func(t *testing.T) {
			want := "Hello, " + name + "!"
			if got := Greeting(name); got != want {
				t.Fatalf("Greeting(%q) = %q, want %q", name, got, want)
			}
		})
	}
}
"""
}
