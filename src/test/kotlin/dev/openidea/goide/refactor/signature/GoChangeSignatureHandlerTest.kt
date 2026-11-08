package dev.openidea.goide.refactor.signature

import com.intellij.refactoring.actions.ChangeSignatureAction
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.lang.GoLanguage

class GoChangeSignatureHandlerTest : LightPlatformTestCase() {
    fun testStandardPlatformChangeSignatureActionFindsTheGoHandler() {
        val file = createFile("signature.go", "package fixture\nfunc Target(value int) int { return value }\n")
        assertEquals(GoLanguage, file.language)
        val target = file.findElementAt(file.text.indexOf("Target") + 2)!!
        val handler = ChangeSignatureAction.getChangeSignatureHandler(target)
        assertTrue("The standard native action must use our typed Go implementation", handler is GoChangeSignatureHandler)
        assertEquals(file, handler!!.findTargetMember(target))
    }
    fun testFastAvailabilityDoesNotClaimOtherLanguages() {
        val file = createFile("notes.txt", "Target(1)")
        val provider = GoRefactoringSupportProvider()
        assertFalse(provider.isAvailable(file))
        assertNull(provider.changeSignatureHandler.findTargetMember(file))
    }
}
