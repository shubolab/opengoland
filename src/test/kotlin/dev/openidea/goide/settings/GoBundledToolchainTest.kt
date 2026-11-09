package dev.openidea.goide.settings

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GoBundledToolchainTest {
    @Test fun emptySelectionUsesTheRelocatedBundleBeforeAnInstalledGo() = fixture { root ->
        val tools = root.resolve("安装目录 空格😀/tools")
        val go = executable(tools.resolve("go/bin/" + filename("go")))
        Files.createDirectories(tools.resolve("go/src/runtime"))
        assertEquals(go.toString(), GoToolchain.resolve("go", "", tools))
        for (name in listOf("gopls", "dlv")) {
            val expected = executable(tools.resolve("bin/" + filename(name)))
            assertEquals(expected.toString(), GoToolchain.resolve(name, "", tools))
        }
    }

    @Test fun explicitCustomSdkWinsAndAnInvalidExplicitPathNeverFallsBack() = fixture { root ->
        val tools = root.resolve("tools")
        executable(tools.resolve("go/bin/" + filename("go")))
        Files.createDirectories(tools.resolve("go/src/runtime"))
        val custom = executable(root.resolve("custom SDK/bin/" + filename("go")))
        assertEquals(custom.toString(), GoToolchain.resolve("go", custom.toString(), tools))
        assertNull(GoToolchain.resolve("go", root.resolve("missing SDK/bin/go").toString(), tools))
        assertNull(GoToolchain.resolve("go", "\u0000invalid", tools))
    }

    @Test fun aPartialSdkAndUnsupportedToolDoNotBecomeBundledExecutables() = fixture { root ->
        val path = executable(root.resolve("tools/go/bin/" + filename("go")))
        assertNull(GoToolchain.bundledExecutable("go", root.resolve("tools")))
        assertNotEquals(path.toString(), GoToolchain.resolve("go", "", root.resolve("tools")))
        assertNull(GoToolchain.bundledExecutable("../untrusted", root.resolve("tools")))
    }

    @Test fun windowsBundleUsesExeNamesForSdkAndLanguageTools() = fixture { root ->
        val tools = root.resolve("tools")
        Files.createDirectories(tools.resolve("go/src/runtime"))
        for (name in listOf("go", "gofmt", "gopls", "dlv")) {
            val expected = executable(tools.resolve(if (name in setOf("go", "gofmt")) "go/bin/$name.exe" else "bin/$name.exe"))
            assertEquals(expected, GoToolchain.bundledExecutable(name, tools, windows = true))
        }
    }

    private fun filename(name: String) = name + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    private fun executable(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "owned fixture")
        path.toFile().setExecutable(true)
        return path.toAbsolutePath().normalize()
    }
    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("open-goland-bundled-tools-").toRealPath()
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
