package dev.openidea.goide.generate.tests

import com.intellij.codeInsight.navigation.GotoTargetPresentationProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement

/** The SDK fallback uses only PsiNamedElement.name and would hide distinct receivers. */
class GoTestNavigationPresentationProvider : GotoTargetPresentationProvider {
    override fun getTargetPresentation(element: PsiElement, hasDifferentNames: Boolean): TargetPresentation? {
        val declaration = element as? GoTestNavigationDeclaration ?: return null
        if (!declaration.isValid()) return null
        return TargetPresentation.builder(declaration.getPresentableText())
            .icon(declaration.getIcon(false))
            .containerText(declaration.getLocationString())
            .presentation()
    }
}
