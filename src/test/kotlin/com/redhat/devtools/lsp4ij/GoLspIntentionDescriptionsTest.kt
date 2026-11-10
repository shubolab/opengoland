package com.redhat.devtools.lsp4ij

import com.google.gson.JsonParser
import com.intellij.DynamicBundle
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.config.IntentionActionMetaData
import com.intellij.codeInsight.intention.impl.config.IntentionActionWrapper
import com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings
import com.intellij.testFramework.LightPlatformTestCase
import java.util.Locale

/** Real native metadata/description loading, with LSP4IJ's own bundled translation. */
class GoLspIntentionDescriptionsTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testChineseFamiliesLoadTheRegisteredNativeDescriptionResources() {
        val metadata = lspMetadata()
        for (entry in metadata) {
            val loader = requireNotNull(entry.loader)
            val translated = DynamicBundle.getResourceBundleLocalized(loader, "messages.LanguageServerBundle", Locale.SIMPLIFIED_CHINESE)
                .getString("lsp.intention.code.action.kind.empty")
            assertEquals("Use the real upstream Chinese translation, not commercial language-pack data", "空", translated)
            val localizedAction = object : IntentionAction by entry.action {
                override fun getFamilyName() = translated
            }
            // Keep the directory from the actually registered plugin descriptor.
            // The old descriptor falls back from LSPIntentionActionN to the translated
            // family name, then fails to find intentionDescriptions/空/description.html.
            val localized = IntentionActionMetaData(localizedAction, loader, entry.myCategory,
                entry.descriptionDirectoryName, entry.isSkipBeforeAfter)
            val text = localized.description.getText()
            assertTrue("The native description must resolve for ${implementation(entry)}", text.contains("<html"))
            assertTrue("The actual native description must contain its upstream body", text.contains("<body>"))
            assertEquals("Empty", localized.descriptionDirectoryName)
        }
    }

    fun testEnglishDescriptionsAndPinnedRuntimeResourceReceiptRemainPresent() {
        for (entry in lspMetadata()) {
            assertTrue("Existing native description must stay readable", entry.description.getText().contains("<html"))
        }
        val loader = LanguageServerWrapper::class.java.classLoader
        val marker = requireNotNull(loader.getResourceAsStream("META-INF/open-goland-document-sync.json"))
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val resource = marker.getAsJsonObject("intentionDescriptions")
        assertNotNull("The compiled/test/runtime plugin must all carry the resource patch receipt", resource)
        assertEquals("META-INF/plugin.xml", resource["resourcePath"].asString)
        assertEquals("Empty", resource["descriptionDirectoryName"].asString)
        assertEquals("1be28f40b53e6084b1f6a26769656b9425bdeaa9949983942b183ae7c629bb68", resource["upstreamSha256"].asString)
        assertEquals("037fb1659ef131fc12005b5b3df0a055fb74e984ec579c504f71b6f321f628ef", resource["patchedSha256"].asString)
        assertEquals(expectedIds, resource.getAsJsonArray("intentionClassNames").map { it.asString }.toSet())
    }

    private fun lspMetadata(): List<IntentionActionMetaData> = IntentionManagerSettings.getInstance().getMetaData().filter {
        implementation(it) in expectedIds
    }.also { entries ->
        // Native settings coalesces equal intention families into one metadata
        // group; all twenty unloaded lazy actions initially have the Empty family.
        assertEquals("The real native lazy-intention family must remain present", 1, entries.size)
        assertEquals("com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionAction0",
            implementation(entries.single()))
    }

    private fun implementation(metadata: IntentionActionMetaData) =
        (metadata.action as? IntentionActionWrapper)?.implementationClassName.orEmpty()

    private companion object {
        val expectedIds = (0 until 20).map {
            "com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionAction$it"
        }.toSet()
    }
}
