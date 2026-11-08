package dev.openidea.goide.project

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class GoModuleRequest(val modulePath: String, val directory: Path, val goExecutable: String, val createExample: Boolean = true) {
    fun validate() {
        require(modulePath.isNotBlank() && modulePath.none { it.isWhitespace() || it == '\u0000' }) { "Enter a module path without whitespace, for example example.com/hello." }
        require(directory.isAbsolute && directory.normalize().parent != null) { "Choose an absolute project directory below a parent folder." }
        require(goExecutable.isNotBlank() && '\u0000' !in goExecutable) { "Choose a Go executable." }
    }
}

/** Generates in an owned staging directory, then reserves a new destination without replacing anything. */
object GoModuleGenerator {
    fun generate(request: GoModuleRequest, cancelled: () -> Boolean = { false }, onOutput: (String) -> Unit = {}): Path {
        request.validate()
        checkCancelled(cancelled)
        val destination = request.directory.normalize()
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw IOException("Project directory already exists: $destination")
        val parent = destination.parent
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, ".openidea-go-module-")
        val moved = mutableListOf<Path>()
        var reserved = false
        var completed = false
        try {
            initialize(request, staging, cancelled, onOutput)
            checkCancelled(cancelled)
            if (request.createExample) {
                Files.writeString(staging.resolve("main.go"), MAIN, StandardCharsets.UTF_8)
                Files.writeString(staging.resolve("greeting.go"), GREETING, StandardCharsets.UTF_8)
                Files.writeString(staging.resolve("greeting_test.go"), TEST, StandardCharsets.UTF_8)
            }
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
            return destination
        } finally {
            deleteOwnedTree(staging)
            if (reserved && !completed) {
                moved.asReversed().forEach { path -> runCatching { Files.deleteIfExists(path) } }
                // Preserve any unexpected files rather than recursively deleting the destination.
                runCatching { Files.deleteIfExists(destination) }
            }
        }
    }

    private fun initialize(request: GoModuleRequest, directory: Path, cancelled: () -> Boolean, onOutput: (String) -> Unit) {
        val builder = ProcessBuilder(request.goExecutable, "mod", "init", request.modulePath)
            .directory(directory.toFile()).redirectErrorStream(true)
        builder.environment()["GOWORK"] = "off"
        val process = builder.start()
        val output = StringBuilder()
        val readError = AtomicReference<Throwable?>()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { stream ->
                    stream.forEachLine { line ->
                        synchronized(output) { if (output.length < 256 * 1024) output.append(line).append('\n') }
                        onOutput(line)
                    }
                }
            } catch (error: Throwable) { readError.set(error) }
        }, "OpenIDEA Go module initialization output").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled)
                if (System.nanoTime() >= deadline) throw IOException("Go module initialization timed out after 60 seconds.")
            }
            reader.join(1000)
            checkCancelled(cancelled)
            if (process.exitValue() != 0) throw IOException("go mod init failed (exit ${process.exitValue()}):\n${synchronized(output) { output.toString() }}")
            readError.get()?.let { throw IOException("Unable to read go mod init output.", it) }
        } finally {
            if (process.isAlive) {
                val children = process.descendants().use { it.toList() }.asReversed()
                children.forEach { it.destroy() }
                process.destroy()
                if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                children.filter { it.isAlive }.forEach { it.destroyForcibly() }
            }
            process.inputStream.close()
            reader.join(1000)
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
