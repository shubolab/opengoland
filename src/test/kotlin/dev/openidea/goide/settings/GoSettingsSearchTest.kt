package dev.openidea.goide.settings

import com.intellij.ide.ui.search.SearchableOptionsRegistrar
import com.intellij.ide.ui.search.SearchableOptionsRegistrarImpl
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.LightPlatformTestCase
import javax.swing.event.DocumentEvent

/** Exercise the real registered search index and configurable-ID filtering, not a token-list mock. */
class GoSettingsSearchTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testVisibleGoOptionLabelsResolveToTheirActualSettingsPages() {
        lateinit var go: GoSettingsConfigurable
        lateinit var lint: GoLintConfigurable
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            go = GoSettingsConfigurable(project)
            lint = GoLintConfigurable(project)
        }
        val registrar = SearchableOptionsRegistrar.getInstance() as SearchableOptionsRegistrarImpl
        registrar.initializeBlocking()
        val pages: MutableSet<Configurable> = linkedSetOf(go, lint)
        fun hits(query: String) = registrar.getConfigurables(
            emptyList(), DocumentEvent.EventType.INSERT, pages, query, project,
        ).contentHits.toSet()

        assertEquals(setOf(go), hits("Indent on Enter in raw strings"))
        assertEquals(setOf(go), hits("CGO_ENABLED"))
        assertEquals(setOf(go), hits("gopls executable"))
        assertEquals(setOf(lint), hits("golangci-lint flags"))
        assertEquals(setOf(lint), hits("Go Vet flags"))
        assertTrue(hits("thissettingdoesnotexistinopengoland").isEmpty())
    }
}
