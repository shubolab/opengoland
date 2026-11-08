package dev.openidea.goide.engine

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import dev.openidea.goide.settings.GoProjectSettings
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EngineClientLifecycleTest {
    private fun project(root: Path, settings: GoProjectSettings): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> root.toString()
                "getService" -> if (args?.firstOrNull() == GoProjectSettings::class.java) settings else null
                "toString" -> "EngineClientLifecycleTestProject"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as Project

    private fun fixture(run: (Path, GoProjectSettings, EngineClient) -> Unit) {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("opengoland-engine-client")
        val previous = System.getProperty("openidea.workspace")
        val executable = root.resolve("engine/bin/opengoland-engine")
        Files.createDirectories(executable.parent)
        Files.writeString(executable, """
            |#!/usr/bin/env python3
            |import json, os, pathlib, sys, time
            |def send(body):
            |    data=json.dumps(body).encode()
            |    sys.stdout.buffer.write(('Content-Length: %d\r\n\r\n' % len(data)).encode()+data)
            |    sys.stdout.buffer.flush()
            |while True:
            |    length=None
            |    while True:
            |        line=sys.stdin.buffer.readline()
            |        if not line: sys.exit(0)
            |        if line in (b'\r\n',b'\n'): break
            |        if line.lower().startswith(b'content-length:'): length=int(line.split(b':',1)[1])
            |    message=json.loads(sys.stdin.buffer.read(length))
            |    method=message['method']
            |    if method=='shutdown':
            |        time.sleep(.2)
            |        sys.exit(0)
            |    if method=='initialize' and pathlib.Path('fail-once').exists():
            |        pathlib.Path('fail-once').unlink()
            |        sys.exit(1)
            |    result={'pid':os.getpid(),'goExecutable':message.get('params',{}).get('goExecutable'),'goos':os.getenv('GOOS')}
            |    if method=='task/start': result={'accepted':True,'id':message['params']['id']}
            |    send({'jsonrpc':'2.0','id':message['id'],'result':result})
        """.trimMargin().trimStart())
        executable.toFile().setExecutable(true)
        System.setProperty("openidea.workspace", root.toString())
        val settings = GoProjectSettings()
        val engine = EngineClient(project(root, settings))
        try {
            run(root, settings, engine)
        } finally {
            engine.dispose()
            if (previous == null) System.clearProperty("openidea.workspace") else System.setProperty("openidea.workspace", previous)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun failedInitializationCanBeRetried() = fixture { root, _, engine ->
        Files.writeString(root.resolve("fail-once"), "")
        assertFailsWith<java.util.concurrent.ExecutionException> { engine.connect().get(10, TimeUnit.SECONDS) }
        val ready = engine.connect().get(10, TimeUnit.SECONDS).asJsonObject
        assertTrue(ready.get("pid").asLong > 0)
        assertTrue(ready.get("goExecutable").asString.endsWith("/go"))
    }

    @Test fun settingsRestartDoesNotLetOldReaderDisconnectNewEngine() = fixture { _, settings, engine ->
        val stopped = CopyOnWriteArrayList<JsonObject>()
        val subscription = engine.listen { method, params -> if (method == "engine/stopped") stopped.add(params) }
        try {
            val first = engine.connect().get(10, TimeUnit.SECONDS).asJsonObject.get("pid").asLong
            engine.request("task/start", JsonObject().apply { addProperty("id", "old-task") }).get(10, TimeUnit.SECONDS)
            settings.state.goos = "linux"
            val second = engine.connect().get(10, TimeUnit.SECONDS).asJsonObject
            assertNotEquals(first, second.get("pid").asLong)
            assertEquals("linux", second.get("goos").asString)
            assertEquals(listOf("old-task"), stopped.single().getAsJsonArray("taskIds").map { it.asString })
            ProcessHandle.of(first).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
            val stillRunning = engine.request("project/info").get(10, TimeUnit.SECONDS).asJsonObject
            assertEquals(second.get("pid").asLong, stillRunning.get("pid").asLong)
            assertEquals(1, stopped.size)
        } finally {
            subscription.close()
        }
    }

    @Test fun disposeShutsDownOwnedEngineAndRejectsReconnect() = fixture { _, _, engine ->
        val pid = engine.connect().get(10, TimeUnit.SECONDS).asJsonObject.get("pid").asLong
        engine.dispose()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
        assertFailsWith<java.util.concurrent.ExecutionException> { engine.connect().get(2, TimeUnit.SECONDS) }
    }
}
