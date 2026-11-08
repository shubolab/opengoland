package dev.openidea.goide.lint

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class GoInspectionProcessTest {
    @Test fun cancellingAnOwnedGoLauncherAlsoTerminatesItsRunningChild() {
        val root = Files.createTempDirectory("openidea-inspection-cancel-").toRealPath()
        var handler: com.intellij.execution.process.KillableProcessHandler? = null
        try {
            Files.writeString(root.resolve("main.go"), "package main\nimport (\"fmt\"; \"time\")\nfunc main() { fmt.Println(\"READY\"); time.Sleep(time.Minute) }\n")
            val ready = CountDownLatch(1)
            val command = GeneralCommandLine("go", "run", "main.go").withWorkDirectory(root.toString()).withEnvironment("GOWORK", "off").withEnvironment("GO111MODULE", "off")
            handler = GoInspectionProcesses.create(command)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { if (event.text.contains("READY")) ready.countDown() }
            })
            handler.startNotify()
            assertTrue(ready.await(20, TimeUnit.SECONDS), "The real Go child did not start")
            val children = handler.process.descendants().use { it.toList() }
            assertTrue(children.isNotEmpty(), "The fixture must exercise a launcher plus its child")
            handler.destroyProcess()
            assertTrue(handler.waitFor(5000), "Cancellation did not terminate the owned Go launcher")
            children.forEach { if (it.isAlive) it.onExit().get(5, TimeUnit.SECONDS) }
            assertTrue(children.none { it.isAlive }, "Cancellation left a Go child running")
        } finally {
            handler?.let { if (!it.isProcessTerminated) { it.killProcess(); it.waitFor(3000) } }
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
