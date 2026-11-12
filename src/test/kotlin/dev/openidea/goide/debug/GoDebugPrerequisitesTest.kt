package dev.openidea.goide.debug

import com.intellij.testFramework.LightPlatformTestCase

class GoDebugPrerequisitesTest : LightPlatformTestCase() {
    fun testDisabledDeveloperModeProducesAnActionableFailureBeforeDelveLaunches() {
        val message = requireNotNull(GoDebugPrerequisites.permissionFailure("Developer mode is currently disabled.", 0))
        assertTrue(message.contains("sudo DevToolsSecurity -enable"))
        assertTrue(message.contains("administrator password"))
        assertTrue(message.contains("Developer mode is currently disabled."))
    }

    fun testEnabledDeveloperModeAllowsDelveLaunch() {
        assertNull(GoDebugPrerequisites.permissionFailure("Developer mode is enabled.", 0))
    }

    fun testFailedPermissionProbeNeverPretendsDebuggingIsAvailable() {
        val message = requireNotNull(GoDebugPrerequisites.permissionFailure("operation not permitted", 1))
        assertTrue(message.contains("operation not permitted"))
    }
}
