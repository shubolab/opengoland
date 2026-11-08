package dev.openidea.goide.lang

import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

class GoCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage() = GoLanguage
    override fun getCodeSample(settingsType: SettingsType) = """
        package main

        import "fmt"

        func main() {
            fmt.Println("Hello, Go")
        }
    """.trimIndent()
    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.USE_TAB_CHARACTER = true
        indentOptions.TAB_SIZE = 4
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
    }
}
