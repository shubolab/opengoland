package dev.openidea.goide.settings

import com.intellij.ide.ui.search.SearchableOptionContributor
import com.intellij.ide.ui.search.SearchableOptionProcessor

/** Index the actual Go form labels without constructing Swing forms or discovering tools. */
class GoSettingsSearchContributor : SearchableOptionContributor() {
    override fun processOptions(processor: SearchableOptionProcessor) {
        val goOptions = listOf(
            "Go executable", "gopls executable", "Delve executable", "Build tags",
            "Target OS (GOOS)", "Target architecture (GOARCH)", "CGO_ENABLED (empty, 0 or 1)",
            "Environment (NAME=value per line)", "Indent on Enter in raw strings",
        )
        val lintOptions = listOf("golangci-lint executable", "Go Vet flags", "golangci-lint flags")
        for (label in goOptions) processor.addOptions(label, null, label, GoSettingsConfigurable.ID, "Go", true)
        for (label in lintOptions) processor.addOptions(label, null, label, GoLintConfigurable.ID, "Go Inspections", true)
    }
}
