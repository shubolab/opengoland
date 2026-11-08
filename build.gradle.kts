import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "dev.openidea"
version = "0.1.0-SNAPSHOT"

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

val buildInputManifest = layout.buildDirectory.file("provenance/build-inputs.json")
val platformUnicodeManifest = layout.buildDirectory.file("provenance/platform-unicode-patch.json")
val preparePlatformUnicodePatch by tasks.registering(Exec::class) {
    description = "Verify and build the product-only public platform Unicode persistence fix."
    inputs.files("scripts/patch-platform-unicode.py", fileTree("product/patches") { include("platform-unicode*") })
    outputs.file(platformUnicodeManifest)
    // Verify cached class bytes even when a prior report exists.
    outputs.upToDateWhen { false }
    commandLine("python3", "scripts/patch-platform-unicode.py", "--sdk", platformPath.get(),
        "--output", platformUnicodeManifest.get().asFile.absolutePath)
}
tasks.test {
    dependsOn(preparePlatformUnicodePatch)
    systemProperty("openidea.platformUnicodePatchManifest", platformUnicodeManifest.get().asFile.absolutePath)
}
val captureBuildInputs by tasks.registering(Exec::class) {
    inputs.files(fileTree("src"), fileTree("engine") { include("**/*.go", "go.mod", "go.sum", "licenses/**") })
    inputs.files(fileTree("scripts") { include("*.py", "*.sh") })
    inputs.files(fileTree("product/patches"))
    inputs.files("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "product/platform.lock.json")
    outputs.file(buildInputManifest)
    commandLine("python3", "scripts/build_inputs.py", "capture", buildInputManifest.get().asFile)
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
    outputs.file(layout.projectDirectory.file("engine/bin/opengoland-engine"))
    commandLine("go", "build", "-o", "bin/opengoland-engine", "./cmd/opengoland-engine")
}

val verifyBuildInputs by tasks.registering(Exec::class) {
    dependsOn(captureBuildInputs, tasks.compileKotlin, buildEngine)
    commandLine("python3", "scripts/build_inputs.py", "verify", buildInputManifest.get().asFile)
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
    from(layout.projectDirectory.file("engine/bin/opengoland-engine")) {
        into(pluginName.map { "$it/bin" })
        filePermissions { unix("rwxr-xr-x") }
    }
    from(layout.projectDirectory.dir("engine/licenses")) {
        into(pluginName.map { "$it/license" })
        filePermissions { unix("rw-r--r--") }
    }
    inputs.files(fileTree("product/patches"), "scripts/patch-lsp4ij.py")
    doLast {
        providers.exec {
            commandLine("python3", "scripts/patch-lsp4ij.py", "--sdk", lspPatchSdk.get(),
                "--plugins", sandboxPluginsDirectory.get().asFile.absolutePath)
        }.result.get().assertNormalExitValue()
    }
}
