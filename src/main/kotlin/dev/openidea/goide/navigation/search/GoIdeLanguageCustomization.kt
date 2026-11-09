package dev.openidea.goide.navigation.search

import com.intellij.lang.IdeLanguageCustomization
import com.intellij.lang.Language
import dev.openidea.goide.lang.GoLanguage

/** Product-level primary language, consumed by the standard native type-search presentation. */
class GoIdeLanguageCustomization : IdeLanguageCustomization() {
    override fun getPrimaryIdeLanguages(): List<Language> = listOf(GoLanguage)
}
