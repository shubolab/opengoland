package dev.openidea.goide.platform

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual private platform classes in isolation: the running test IDE and immutable source SDK stay untouched. */
class PlatformUnicodePersistenceTest {
    @Test fun completePatchedPlatformClassSetMatchesTheRecordedArtifact() = isolated { loader, report ->
        val jar = Path.of(report.get("patchJar").asString)
        assertEquals(report.get("patchedJarSha256").asString, sha(Files.readAllBytes(jar)))
        val markerName = report.get("marker").asString
        ZipFile(jar.toFile()).use { zip ->
            val marker = JsonParser.parseString(zip.getInputStream(zip.getEntry(markerName)).bufferedReader().use { it.readText() }).asJsonObject
            assertEquals(report.get("patchSha256").asString, marker.get("patchSha256").asString)
            val recorded = marker.getAsJsonObject("classSha256").entrySet().associate { it.key to it.value.asString }
            val actual = zip.entries().asSequence().map { it.name }.filter {
                it == "com/intellij/openapi/util/JDOMUtil.class" || it.startsWith("com/intellij/openapi/util/JDOMUtil$") && it.endsWith(".class")
            }.toSet()
            assertEquals(recorded.keys, actual)
            assertTrue(recorded.size >= 6)
            for ((name, digest) in recorded) {
                val bytes = requireNotNull(loader.getResourceAsStream(name)).use { it.readAllBytes() }
                assertEquals(digest, sha(bytes), "The isolated loader must resolve every recorded nested class")
            }
        }
        val utility = loader.loadClass("com.intellij.openapi.util.JDOMUtil")
        assertSame(loader, utility.classLoader)
        assertEquals(jar.toUri(), utility.protectionDomain.codeSource.location.toURI())
        val source = Path.of(report.get("sourceSdk").asString).resolve(report.get("upstreamJarRelativePath").asString)
        assertEquals(report.get("upstreamJarSha256").asString, sha(Files.readAllBytes(source)))
    }

    @Test fun legalUnicodeScalarsSurviveWhileIllegalXmlAndUnpairedSurrogatesRemainFiltered() = isolated { loader, _ ->
        val filter = loader.loadClass("com.intellij.openapi.util.JDOMUtil").getMethod("removeControlChars", String::class.java)
        val valid = "ASCII 中文 😀 𐐀 " + String(Character.toChars(0x10FFFF)) + "\t\n\r"
        assertEquals(valid, filter.invoke(null, valid))
        val invalid = "\u0000A\u0001B\u000BC\u001FD\uFFFEE\uFFFFF\uD800G\uDC00H😀𐐀"
        assertEquals("ABCDEFGH😀𐐀", filter.invoke(null, invalid))
        assertEquals("😀X𐐀", filter.invoke(null, "😀\u0001X\uD800𐐀\uDC00"))
    }

    @Test fun realTrustedPathsStateRoundTripKeepsDistinctSupplementaryPathsAndDoesNotTrustItsSibling() = isolated { loader, _ ->
        val stateClass = loader.loadClass("com.intellij.ide.impl.TrustedPaths\$State")
        val elementClass = loader.loadClass("org.jdom.Element")
        val serializer = loader.loadClass("com.intellij.util.xmlb.XmlSerializer")
        val root = Path.of("owned trust fixture").toAbsolutePath().normalize()
        val trustedEmoji = root.resolve("接口 实现_😀")
        val untrustedSibling = root.resolve("接口 实现_")
        val trustedSupplementary = root.resolve("接口𐐀")
        val original = linkedMapOf(trustedEmoji.toString() to true, untrustedSibling.toString() to false, trustedSupplementary.toString() to true)
        val state = stateClass.getConstructor(Map::class.java).newInstance(original)
        val element = serializer.getMethod("serialize", Any::class.java).invoke(null, state)
        val utility = loader.loadClass("com.intellij.openapi.util.JDOMUtil")
        val xml = utility.getMethod("writeElement", elementClass).invoke(null, element) as String
        assertTrue(xml.contains("😀")); assertTrue(xml.contains("𐐀"))
        val restored = serializer.getMethod("deserialize", elementClass, Class::class.java).invoke(null, element, stateClass)
        assertEquals(original, stateClass.getField("trustedPaths").get(restored))
        val storeClass = loader.loadClass("com.intellij.ide.impl.TrustedPaths")
        val store = storeClass.getConstructor().newInstance()
        storeClass.getMethod("loadState", Any::class.java).invoke(store, restored)
        val lookup = storeClass.getMethod("getProjectPathTrustedState", Path::class.java)
        assertEquals("YES", lookup.invoke(store, trustedEmoji).toString())
        assertEquals("NO", lookup.invoke(store, untrustedSibling).toString())
        assertEquals("YES", lookup.invoke(store, trustedSupplementary).toString())
        assertEquals("UNSURE", lookup.invoke(store, root.resolve("接口 实现_😃")).toString())
        assertFalse(xml.contains("\u0001"))
    }

    private fun isolated(action: (URLClassLoader, JsonObject) -> Unit) {
        val manifest = requireNotNull(System.getProperty("openidea.platformUnicodePatchManifest")) {
            "Build the private public-source platform patch before running its persistence regressions"
        }
        val report = JsonParser.parseString(Files.readString(Path.of(manifest))).asJsonObject
        val patch = Path.of(report.get("patchJar").asString)
        val sdk = Path.of(report.get("sourceSdk").asString)
        val libraryRoot = if (Files.isDirectory(sdk.resolve("Contents/lib"))) sdk.resolve("Contents/lib") else sdk.resolve("lib")
        val dependencies = Files.walk(libraryRoot).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }
        val urls = (listOf(patch) + dependencies).map { it.toUri().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            try { thread.contextClassLoader = loader; action(loader, report) }
            finally { thread.contextClassLoader = previous }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
