import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "dev.openidea"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

repositories {
    maven {
        name = "projectHydratedMaven"
        url = uri(".tools/maven")
    }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val platformPath = providers.gradleProperty("platformPath")
    .orElse(providers.environmentVariable("OPENIDEA_PLATFORM_PATH"))
    .orElse(providers.provider {
        val openSourcePlatform = file(".tools/platform/idea-2026.1.4/IntelliJ IDEA.app")
        if (openSourcePlatform.isDirectory) openSourcePlatform.absolutePath
        else "${System.getProperty("user.home")}/Applications/PyCharm.app"
    })
val lspPatchSdk = platformPath
val platformContentsPath = platformPath.map { path ->
    val root = file(path)
    if (root.resolve("Contents/Resources/product-info.json").isFile) root.resolve("Contents").absolutePath
    else root.absolutePath
}
val engineExecutableName = if (System.getProperty("os.name").startsWith("Windows")) "opengoland-engine.exe" else "opengoland-engine"
val pythonCommand = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
val goBuilder = providers.gradleProperty("goBuilder").orElse("go")
val platformPinSuffix = if (System.getProperty("os.name").startsWith("Windows")) "-windows" else ""
fun platformRepairPin(name: String) = "product/patches/platform-$name$platformPinSuffix-2026.1.4.json"

// Compile against the same pinned public-source API that is shipped at runtime.
// The downloaded plugin and SDK stay read-only; only an owned build copy is patched.
val upstreamLspCompileJar = configurations.named("compileClasspath").map { configuration ->
    configuration.files.single { it.name == "lsp4ij-0.21.0.jar" }
}
val lspCompilePlugins = layout.buildDirectory.dir("lsp4ij-compile")
val patchedLspCompileJar = lspCompilePlugins.map { it.file("lsp4ij/lib/lsp4ij-0.21.0.jar") }
val prepareLspCompilePatch by tasks.registering(Exec::class) {
    description = "Build the pinned LSP4IJ API in a private compiler dependency."
    inputs.files(upstreamLspCompileJar.map { jar -> jar.parentFile.listFiles { file -> file.extension == "jar" }!!.toList() })
    inputs.files("scripts/patch-lsp4ij.py", "scripts/source_patch.py", fileTree("product/patches") { include("lsp4ij-*") })
    inputs.property("platformPath", platformPath)
    outputs.file(patchedLspCompileJar)
    // Reconstruct from the authenticated original on each invocation rather than
    // trusting an unverified output. The patch writer produces deterministic bytes.
    outputs.upToDateWhen { false }
    doFirst {
        copy {
            from(upstreamLspCompileJar.get().parentFile) { include("*.jar") }
            into(lspCompilePlugins.get().dir("lsp4ij/lib"))
        }
    }
    commandLine(pythonCommand, "scripts/patch-lsp4ij.py", "--sdk", lspPatchSdk.get(),
        "--plugins", lspCompilePlugins.get().asFile.absolutePath)
}
sourceSets.named("main") {
    compileClasspath = files(compileClasspath.filter { it.name != "lsp4ij-0.21.0.jar" }, patchedLspCompileJar)
        .builtBy(prepareLspCompilePatch)
}
sourceSets.named("test") {
    compileClasspath = files(compileClasspath.filter { it.name != "lsp4ij-0.21.0.jar" }, patchedLspCompileJar)
        .builtBy(prepareLspCompilePatch)
}

val buildInputManifest = layout.buildDirectory.file("provenance/build-inputs.json")
val platformUnicodeManifest = layout.buildDirectory.file("provenance/platform-unicode-patch.json")
val platformUnicodeJar = layout.buildDirectory.file("platform-patches/unicode/util-8.jar")
val preparePlatformUnicodePatch by tasks.registering(Exec::class) {
    description = "Verify and build the product-only public platform Unicode persistence fix."
    inputs.files("scripts/patch-platform-unicode.py", "scripts/source_patch.py", fileTree("product/patches") { include("platform-unicode*") })
    outputs.files(platformUnicodeManifest, platformUnicodeJar)
    // Verify cached class bytes even when a prior report exists.
    outputs.upToDateWhen { false }
    commandLine(pythonCommand, "scripts/patch-platform-unicode.py", "--sdk", platformPath.get(),
        "--pin", platformRepairPin("unicode"),
        "--cache-name", "platform-unicode$platformPinSuffix",
        "--jar-output", platformUnicodeJar.get().asFile.absolutePath,
        "--output", platformUnicodeManifest.get().asFile.absolutePath)
}
val platformPsiListenerManifest = layout.buildDirectory.file("provenance/platform-psi-listener-patch.json")
val platformPsiListenerJar = layout.buildDirectory.file("platform-patches/psi-listener/intellij.platform.lang.impl.jar")
val preparePlatformPsiListenerPatch by tasks.registering(Exec::class) {
    description = "Verify and build the public platform listener initialization repair."
    inputs.files("scripts/patch-platform-unicode.py", "scripts/source_patch.py", fileTree("product/patches") { include("platform-psi-listener*") })
    inputs.property("platformPath", platformPath)
    outputs.files(platformPsiListenerManifest, platformPsiListenerJar)
    outputs.upToDateWhen { false }
    commandLine(pythonCommand, "scripts/patch-platform-unicode.py", "--sdk", platformPath.get(),
        "--pin", platformRepairPin("psi-listener"),
        "--marker", "META-INF/open-goland-platform-psi-listener.json",
        "--cache-name", "platform-psi-listener$platformPinSuffix",
        "--jar-output", platformPsiListenerJar.get().asFile.absolutePath,
        "--output", platformPsiListenerManifest.get().asFile.absolutePath)
}
tasks.test {
    dependsOn(preparePlatformUnicodePatch, preparePlatformPsiListenerPatch)
    systemProperty("openidea.platformUnicodePatchManifest", platformUnicodeManifest.get().asFile.absolutePath)
    systemProperty("openidea.platformPsiListenerPatchManifest", platformPsiListenerManifest.get().asFile.absolutePath)
    inputs.files(platformUnicodeManifest, platformPsiListenerManifest)
    val originalRuntime = classpath
    classpath = files(originalRuntime.elements.map { locations ->
        val upstream = file("${platformContentsPath.get()}/lib/intellij.platform.lang.impl.jar").canonicalFile
        val runtime = locations.map { it.asFile }
        check(runtime.count { it.canonicalFile == upstream } == 1) { "Expected exactly one pinned platform language implementation JAR" }
        val unicodeUpstream = file("${platformContentsPath.get()}/lib/util-8.jar").canonicalFile
        check(runtime.count { it.canonicalFile == unicodeUpstream } == 1) { "Expected exactly one pinned platform utility JAR" }
        runtime.map {
            when (it.canonicalFile) {
                upstream -> platformPsiListenerJar.get().asFile
                unicodeUpstream -> platformUnicodeJar.get().asFile
                else -> it
            }
        }
    }).builtBy(preparePlatformUnicodePatch, preparePlatformPsiListenerPatch)
}
val captureBuildInputs by tasks.registering(Exec::class) {
    inputs.files(fileTree("src"), fileTree("engine") { include("**/*.go", "go.mod", "go.sum", "licenses/**") })
    inputs.files(fileTree("scripts") { include("**/*.py", "**/*.sh", "**/*.ps1", "**/*.iss") })
    inputs.files(fileTree(".github/workflows") { include("**/*.yml", "**/*.yaml") })
    inputs.files(fileTree("product/patches"))
    inputs.files("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "product/platform.lock.json", ".gitattributes")
    inputs.files("product/go-tools.lock.json")
    inputs.files(fileTree("product") { include("**/*windows*.json") })
    outputs.file(buildInputManifest)
    commandLine(pythonCommand, "scripts/build_inputs.py", "capture", buildInputManifest.get().asFile)
}

dependencies {
    intellijPlatform {
        local(platformPath)
        plugin("com.redhat.devtools.lsp4ij", "0.21.0")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test-junit"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(JavaVersion.current().majorVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    dependsOn(captureBuildInputs)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

intellijPlatform {
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
    buildSearchableOptions = false
    pluginConfiguration {
        id = "dev.openidea.go"
        name = "Go Workbench"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
    }
}

tasks.runIde {
    maxHeapSize = "2g"
    systemProperty("idea.is.internal", "true")
    systemProperty("openidea.workspace", project.projectDir.absolutePath)
}

val buildEngine by tasks.registering(Exec::class) {
    dependsOn(captureBuildInputs)
    group = "build"
    description = "Build the Go tooling service for the host architecture."
    workingDir(layout.projectDirectory.dir("engine"))
    inputs.files(fileTree("engine") { exclude("bin/**") })
    inputs.property("goBuilder", goBuilder)
    outputs.file(layout.projectDirectory.file("engine/bin/$engineExecutableName"))
    commandLine(goBuilder.get(), "build", "-o", "bin/$engineExecutableName", "./cmd/opengoland-engine")
}

val verifyBuildInputs by tasks.registering(Exec::class) {
    dependsOn(captureBuildInputs, tasks.compileKotlin, buildEngine)
    commandLine(pythonCommand, "scripts/build_inputs.py", "verify", buildInputManifest.get().asFile)
}

tasks.jar {
    dependsOn(verifyBuildInputs)
    from(buildInputManifest) {
        into("META-INF")
        rename { "openidea-build-inputs.json" }
    }
}

tasks.withType<PrepareSandboxTask>().configureEach {
    dependsOn(buildEngine)
    from(layout.projectDirectory.file("engine/bin/$engineExecutableName")) {
        into(pluginName.map { "$it/bin" })
        filePermissions { unix("rwxr-xr-x") }
    }
    from(layout.projectDirectory.dir("engine/licenses")) {
        into(pluginName.map { "$it/license" })
        filePermissions { unix("rw-r--r--") }
    }
    inputs.files(fileTree("product/patches"), "scripts/patch-lsp4ij.py", "scripts/source_patch.py")
    doLast {
        providers.exec {
            commandLine(pythonCommand, "scripts/patch-lsp4ij.py", "--sdk", lspPatchSdk.get(),
                "--plugins", sandboxPluginsDirectory.get().asFile.absolutePath)
        }.result.get().assertNormalExitValue()
    }
}
