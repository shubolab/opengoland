package dev.openidea.goide.icons

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LightVirtualFile
import dev.openidea.goide.lang.GoFileType
import dev.openidea.goide.lang.GoModFileType
import dev.openidea.goide.lang.GoTemplateFileType
import dev.openidea.goide.lang.GoWorkFileType
import dev.openidea.goide.run.corpus.GoFuzzCorpusFileType
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JLabel

/** Actual plugin extension and SDK SVG loader; the render atlas is not an IDE screenshot. */
class GoFileIconsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testRegisteredTestIconProviderPreservesGoLanguageAndIgnoresOtherFileKinds() {
        val provider = FileIconProvider.EP_NAME.extensionList.single { it is GoFileIconProvider }
        val test = LightVirtualFile("greeting_test.go", GoFileType.INSTANCE, "package greeting")
        val source = LightVirtualFile("greeting.go", GoFileType.INSTANCE, "package greeting")
        assertSame(GoIcons.Test, provider.getIcon(test, 0, project))
        assertSame("A test source must keep the Go parser and LSP mapping", source.fileType, test.fileType)
        assertNull(provider.getIcon(source, 0, project))
        assertNull(provider.getIcon(LightVirtualFile("greeting_Test.go", GoFileType.INSTANCE, ""), 0, project))
        assertNull(provider.getIcon(LightVirtualFile("notes_test.go", PlainTextFileType.INSTANCE, ""), 0, project))
        assertNull(provider.getIcon(LightVirtualFile("seed_test.go", GoFuzzCorpusFileType.INSTANCE, ""), 0, project))
    }

    fun testNativeSvgRenderingDistinguishesFileKindsInLightAndDarkAndExportsTheAtlas() {
        // Headless tests use DummyIconManager for AllIcons. Check the platform
        // selections here; their actual UI visibility is a native acceptance step.
        assertSame(AllIcons.Nodes.Template, GoTemplateFileType.INSTANCE.icon)
        assertSame(AllIcons.FileTypes.Text, GoFuzzCorpusFileType.INSTANCE.icon)
        val files = listOf(Entry("Go source", GoFileType.INSTANCE.icon), Entry("Go test", GoIcons.Test),
            Entry("go.mod", GoModFileType.INSTANCE.icon), Entry("go.work", GoWorkFileType.INSTANCE.icon),
            Entry("Go template", GoTemplateFileType.INSTANCE.icon, "/expui/nodes/template.svg"),
            Entry("Fuzz corpus", GoFuzzCorpusFileType.INSTANCE.icon, "/expui/fileTypes/text.svg"))
        for (dark in listOf(false, true)) {
            val signatures = files.take(4).map { (name, icon) ->
                val image = render(icon, dark)
                assertTrue("$name must have a visible native SVG", pixels(image).count { it ushr 24 != 0 } > 20)
                pixels(image).contentHashCode()
            }
            assertEquals("Go file kinds must no longer share one GO tile", 4, signatures.distinct().size)
            for ((name, icon) in files.take(4)) {
                assertEquals("$name must leave its corner transparent", 0, render(icon, dark).getRGB(0, 0) ushr 24)
            }
            writeAtlas(files, dark)
        }
        assertNotNull("The redistributed mascot attribution must be packaged with the icons",
            GoIcons::class.java.classLoader.getResource("META-INF/GO-ICONS-NOTICE.txt"))
    }

    private fun drawable(icon: Icon, resource: String?): Icon {
        // Atlas-only platform resource preview, independent of headless AllIcons
        // placeholders. This does not prove the product painted that selection.
        return if (resource != null) IconLoader.getIcon(resource, AllIcons::class.java) else icon
    }

    private fun render(icon: Icon, dark: Boolean, resource: String? = null): BufferedImage {
        val selected = IconLoader.getDarkIcon(drawable(icon, resource), dark)
        assertEquals(16, selected.iconWidth)
        assertEquals(16, selected.iconHeight)
        return BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try { selected.paintIcon(JLabel(), graphics, 0, 0) } finally { graphics.dispose() }
        }
    }

    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private data class Entry(val name: String, val icon: Icon, val resource: String? = null)

    private fun writeAtlas(files: List<Entry>, dark: Boolean) {
        val tools = listOf(Entry("Modules / SDK", AllIcons.Toolwindows.Dependencies, "/expui/toolwindows/dependencies.svg"),
            Entry("Coverage", AllIcons.Toolwindows.ToolWindowCoverage, "/expui/toolwindows/coverage.svg"),
            Entry("Profiles", AllIcons.Toolwindows.ToolWindowProfiler, "/expui/toolwindows/profiler.svg"),
            Entry("Inspections", AllIcons.Toolwindows.ToolWindowInspection, "/expui/toolwindows/toolWindowInspection.svg"))
        val image = BufferedImage(800, 246, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = if (dark) Color(0x2B2D30) else Color(0xF7F8FA)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = if (dark) Color(0xDFE1E5) else Color(0x31343B)
            graphics.font = Font("Dialog", Font.PLAIN, 11)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.drawString("SDK SVG previews: ${if (dark) "dark" else "light"}; 16px / 2x / 4x; not a desktop screenshot", 8, 16)
            for ((index, entry) in (files + tools).withIndex()) {
                val x = index % 5 * 160; val y = index / 5 * 110 + 24
                graphics.drawString(entry.name, x + 8, y + 14)
                for ((scale, offset) in listOf(1 to 8, 2 to 40, 4 to 88)) {
                    val scaled = graphics.create() as java.awt.Graphics2D
                    try {
                        scaled.translate(x + offset, y + 28)
                        scaled.scale(scale.toDouble(), scale.toDouble())
                        IconLoader.getDarkIcon(drawable(entry.icon, entry.resource), dark).paintIcon(JLabel(), scaled, 0, 0)
                    } finally { scaled.dispose() }
                }
            }
        } finally { graphics.dispose() }
        val output = Path.of(".tools/continuation-20261113/icon-repair/render").toAbsolutePath()
        Files.createDirectories(output)
        ImageIO.write(image, "png", output.resolve("sdk-${if (dark) "dark" else "light"}.png").toFile())
    }
}
