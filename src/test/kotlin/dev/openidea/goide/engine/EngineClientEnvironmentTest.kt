package dev.openidea.goide.engine

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightPlatformTestCase
import dev.openidea.goide.process.GoOwnedTestExecutable
import dev.openidea.goide.settings.GoToolchain
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real native engine pipe and child Go lookup, including Windows Path/PATH inheritance. */
class EngineClientEnvironmentTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testSelectedSdkAndItsCompleteEnvironmentReachTheOwnedEngineProcess() {
        val root = Files.createTempDirectory("open Goland 环境 😀 ").toRealPath()
        val program = root.resolve("engine/bin/" + GoOwnedTestExecutable.name("opengoland-engine"))
        val selected = Path.of(requireNotNull(GoToolchain.resolve("go",
            System.getProperty("opengoland.testGo") ?: System.getenv("OPENGOLAND_GO_TEST").orEmpty()))).toRealPath()
        val sdk = selected.parent.parent
        val expectedVersion = Files.readString(sdk.resolve("VERSION")).lineSequence().first()
        val token = UUID.randomUUID().toString()
        val prior = System.getProperty("openidea.workspace")
        val realProject = project
        val proxy = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, arguments ->
            if (method.name == "getBasePath") root.toString()
            else try { method.invoke(realProject, *arguments.orEmpty()) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
        } as Project
        var client: EngineClient? = null
        try {
            GoOwnedTestExecutable.compile(program, """
                package main
                import ("bufio"; "encoding/json"; "fmt"; "io"; "os"; "os/exec"; "path/filepath"; "strconv"; "strings")
                func main() {
                    executable, err := os.Executable(); if err != nil { panic(err) }
                    root := filepath.Dir(filepath.Dir(filepath.Dir(executable)))
                    record := map[string]any{"pid":os.Getpid(), "token":"$token", "executable":executable}
                    owned, _ := json.Marshal(record)
                    if err := os.WriteFile(filepath.Join(root,"owned.json"),owned,0600); err != nil { panic(err) }
                    input := bufio.NewReader(os.Stdin)
                    for {
                        length := -1
                        for { line, err := input.ReadString('\n'); if err != nil { return }; line = strings.TrimSpace(line)
                            if line == "" { break }; if strings.HasPrefix(strings.ToLower(line), "content-length:") {
                                length, err = strconv.Atoi(strings.TrimSpace(strings.SplitN(line,":",2)[1])); if err != nil { panic(err) }
                            }
                        }
                        if length < 0 { panic("Missing length") }
                        data := make([]byte,length); if _,err := io.ReadFull(input,data); err != nil { panic(err) }
                        var message struct { ID json.RawMessage; Method string; Params map[string]any }
                        if err := json.Unmarshal(data,&message); err != nil { panic(err) }
                        if message.Method == "shutdown" { return }
                        goPath, err := exec.LookPath("go"); if err != nil { panic(err) }
                        output, err := exec.Command(goPath,"env","-json","GOROOT","GOVERSION").CombinedOutput()
                        if err != nil { panic(string(output)) }
                        var goEnv map[string]string; if err := json.Unmarshal(output,&goEnv); err != nil { panic(err) }
                        paths := []string{}
                        for _, entry := range os.Environ() { key,_,_ := strings.Cut(entry,"="); if strings.EqualFold(key,"PATH") { paths = append(paths,entry) } }
                        cwd,err := os.Getwd(); if err != nil { panic(err) }
                        record["paths"] = paths; record["pathGo"] = goPath; record["goEnv"] = goEnv; record["cwd"] = cwd
                        record["environmentRoot"] = os.Getenv("GOROOT"); record["tokenEnvironment"] = os.Getenv("OPENGOLAND_ENGINE_ENV_TOKEN")
                        record["systemRoot"] = os.Getenv("SystemRoot"); record["selectedGo"] = message.Params["goExecutable"]
                        packet,err := json.Marshal(map[string]any{"jsonrpc":"2.0","id":message.ID,"result":record}); if err != nil { panic(err) }
                        fmt.Fprintf(os.Stdout,"Content-Length: %d\r\n\r\n",len(packet)); if _,err := os.Stdout.Write(packet); err != nil { panic(err) }
                    }
                }
            """.trimIndent())
            System.setProperty("openidea.workspace", root.toString())
            val settings = GoToolchain.Settings(selected.toString(), "", "", "", "", "", "",
                "GOWORK=off\nGOENV=off\nGOTOOLCHAIN=local\nGOPROXY=off\nGOTELEMETRY=off\nOPENGOLAND_ENGINE_ENV_TOKEN=$token")
            val engine = EngineClient(proxy)
            client = engine
            val ready = engine.connect(settings).get(15, TimeUnit.SECONDS).asJsonObject
            assertEquals(token, ready.get("token").asString)
            assertEquals(token, ready.get("tokenEnvironment").asString)
            assertEquals(selected, Path.of(ready.get("selectedGo").asString).toRealPath())
            assertEquals(selected, Path.of(ready.get("pathGo").asString).toRealPath())
            assertEquals(sdk, Path.of(ready.get("environmentRoot").asString).toRealPath())
            assertEquals(sdk, Path.of(ready.getAsJsonObject("goEnv").get("GOROOT").asString).toRealPath())
            assertEquals(expectedVersion, ready.getAsJsonObject("goEnv").get("GOVERSION").asString)
            assertEquals(root, Path.of(ready.get("cwd").asString).toRealPath())
            val paths = ready.getAsJsonArray("paths").map { it.asString }
            val nativePath = paths.single { it.substringBefore('=') == "PATH" ||
                System.getProperty("os.name").startsWith("Windows") && it.substringBefore('=').equals("PATH", true) }
            assertEquals(selected.parent, Path.of(nativePath.substringAfter('=').substringBefore(java.io.File.pathSeparator)).toRealPath())
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertEquals("The native engine must receive one case-insensitive PATH entry", 1, paths.size)
                assertEquals(System.getenv("SystemRoot"), ready.get("systemRoot").asString)
            }
            println("Owned engine selected ${ready.getAsJsonObject("goEnv")}; native PATH entries=${paths.map { it.substringBefore('=') }}")
        } finally {
            client?.dispose()
            try {
                val ownedFile = root.resolve("owned.json")
                if (Files.exists(ownedFile)) {
                    val owned = JsonParser.parseString(Files.readString(ownedFile)).asJsonObject
                    check(owned.get("token").asString == token)
                    ProcessHandle.of(owned.get("pid").asLong).ifPresent { handle ->
                        try { handle.onExit().get(5, TimeUnit.SECONDS) }
                        catch (_: java.util.concurrent.TimeoutException) {
                            // Natural exit can make command() disappear between
                            // observations. Identity is required only before a kill.
                            check(!handle.isAlive || handle.info().command().map { Path.of(it).toRealPath() == program.toRealPath() }.orElse(false))
                            if (handle.isAlive) handle.destroyForcibly()
                            handle.onExit().get(5, TimeUnit.SECONDS)
                        }
                        check(!handle.isAlive)
                    }
                }
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (true) {
                        try { Files.deleteIfExists(path); break }
                        catch (failure: AccessDeniedException) {
                            if (System.nanoTime() >= deadline) throw failure
                            Thread.sleep(20)
                        }
                    }
                } }
            } finally {
                if (prior == null) System.clearProperty("openidea.workspace") else System.setProperty("openidea.workspace", prior)
            }
        }
    }
}
