package dev.openidea.goide.terminal

import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpecConflictStrategy
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpecInfo
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpecsProvider
import org.jetbrains.plugins.terminal.block.completion.spec.ShellDataGenerators
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpec
import org.jetbrains.plugins.terminal.block.completion.spec.dsl.ShellCommandContext
import org.jetbrains.plugins.terminal.block.completion.spec.dsl.ShellOptionContext

/** Terminal completion for the commands users type most often in Go projects. */
class GoShellCommandSpecsProvider : ShellCommandSpecsProvider {
    override fun getCommandSpecs(): List<ShellCommandSpecInfo> = listOf(
        ShellCommandSpecInfo.create(
            ShellCommandSpec("go") {
                displayName("go")
                description("Go tool")
                requiresSubcommand()
                topLevelOptions()
                subcommands {
                    subcommand("bug") { displayName("bug"); description("start a bug report") }
                    subcommand("build") { packageCommand("build", "compile packages and dependencies") }
                    subcommand("clean") { cleanCommand() }
                    subcommand("doc") { packageCommand("doc", "show documentation for a package or symbol") }
                    subcommand("env") { envCommand() }
                    subcommand("fix") { packageCommand("fix", "apply Go rewrites") }
                    subcommand("fmt") { packageCommand("fmt", "gofmt package sources") }
                    subcommand("generate") { packageCommand("generate", "run Go generators") }
                    subcommand("get") { getCommand() }
                    subcommand("help") { helpCommand() }
                    subcommand("install") { packageCommand("install", "compile and install packages") }
                    subcommand("list") { listCommand() }
                    subcommand("mod") { modCommand() }
                    subcommand("run") { runCommand() }
                    subcommand("telemetry") {
                        displayName("telemetry")
                        description("manage Go telemetry data and settings")
                        argument { displayName("mode"); suggestions("off", "local", "on") }
                    }
                    subcommand("test") { testCommand() }
                    subcommand("tool") { toolCommand() }
                    subcommand("version") {
                        displayName("version")
                        description("print Go version")
                        flag("-m", "print module version information")
                    }
                    subcommand("vet") { vetCommand() }
                    subcommand("work") { workCommand() }
                }
            },
            ShellCommandSpecConflictStrategy.REPLACE,
        ),
    )

    private fun ShellCommandContext.topLevelOptions() {
        valueOption("-C", "dir")
        flag("-v", "print commands as packages are compiled")
        flag("-work", "print the temporary work directory")
        flag("-x", "print commands")
        modOption()
        valueOption("-modfile", "file")
        valueOption("-tags", "list")
    }

    private fun ShellCommandContext.packageCommand(commandName: String, description: String) {
        displayName(commandName)
        description(description)
        commonBuildOptions()
        packagesArgument()
    }

    private fun ShellCommandContext.cleanCommand() {
        displayName("clean")
        description("remove object files and cached files")
        flag("-cache", "remove the entire build cache")
        flag("-modcache", "remove the module cache")
        flag("-testcache", "expire all test results in the test cache")
        flag("-i", "remove installed archives")
        flag("-r", "remove package objects")
        flag("-fuzzcache", "remove fuzz cache")
    }

    private fun ShellCommandContext.envCommand() {
        displayName("env")
        description("print Go environment information")
        flag("-json", "print environment as JSON")
        flag("-u", "unset named variables")
        argument { variadic(); displayName("name"); suggestions("GOARCH", "GOOS", "GOPATH", "GOROOT", "GOMOD", "GOWORK") }
    }

    private fun ShellCommandContext.getCommand() {
        displayName("get")
        description("add dependencies and update go.mod")
        flag("-d", "download without installing")
        flag("-t", "add test dependencies")
        flag("-u", "update modules providing dependencies")
        valueOption("-mod", "mode", "mod", "readonly", "vendor")
        valueOption("-modfile", "file")
        valueOption("-tags", "list")
        packagesArgument()
    }

    private fun ShellCommandContext.listCommand() {
        displayName("list")
        description("list packages or modules")
        flag("-e", "report all errors")
        valueOption("-f", "template")
        flag("-json", "print package data as JSON")
        flag("-m", "list modules")
        flag("-test", "report test packages")
        valueOption("-tags", "list")
        packagesArgument()
    }

    private fun ShellCommandContext.helpCommand() {
        displayName("help")
        description("show help for a command or topic")
        argument {
            displayName("command-or-topic")
            suggestions(
                "bug", "build", "clean", "doc", "env", "fix", "fmt", "generate", "get", "install", "list",
                "mod", "run", "telemetry", "test", "tool", "version", "vet", "work",
                "buildconstraint", "buildjson", "buildmode", "c", "cache", "environment", "filetype", "goauth",
                "go.mod", "gopath", "goproxy", "importpath", "modules", "module-auth", "packages", "private",
                "testflag", "testfunc", "vcs",
            )
        }
    }

    private fun ShellCommandContext.modCommand() {
        displayName("mod")
        description("module maintenance")
        requiresSubcommand()
        subcommands {
            subcommand("download") { moduleLeaf("download", "download modules") }
            subcommand("edit") { moduleEdit() }
            subcommand("graph") { moduleLeaf("graph", "print the module graph") }
            subcommand("init") { moduleInit() }
            subcommand("tidy") { moduleLeaf("tidy", "add missing modules and remove unused modules") }
            subcommand("vendor") { moduleLeaf("vendor", "make vendored dependencies") }
            subcommand("verify") { moduleLeaf("verify", "verify dependencies") }
            subcommand("why") { moduleWhy() }
        }
    }

    private fun ShellCommandContext.moduleLeaf(commandName: String, description: String) {
        displayName(commandName)
        description(description)
        valueOption("-modfile", "file")
    }

    private fun ShellCommandContext.moduleEdit() {
        moduleLeaf("edit", "edit go.mod from flags")
        flag("-json", "print final go.mod in JSON")
        flag("-dropreplace", "drop module replacements")
        flag("-fmt", "format go.mod")
        flag("-require", "add a requirement")
        flag("-retract", "retract a version")
    }

    private fun ShellCommandContext.moduleInit() {
        displayName("init")
        description("initialize a new module")
        argument { displayName("module-path") }
    }
    private fun ShellCommandContext.moduleWhy() {
        moduleLeaf("why", "explain why a module is needed")
        flag("-m", "explain inclusion of a module")
        flag("-vendor", "explain vendor selection")
        argument { displayName("package-or-module") }
    }

    private fun ShellCommandContext.runCommand() {
        displayName("run")
        description("compile and run a Go program")
        commonBuildOptions()
        argument { displayName("package-or-file"); variadic(); suggestions(ShellDataGenerators.fileSuggestionsGenerator(false)) }
    }

    private fun ShellCommandContext.testCommand() {
        displayName("test")
        description("test Go packages")
        commonBuildOptions()
        testOptions()
        valueOption("-run", "regexp")
        valueOption("-bench", "regexp")
        valueOption("-count", "n")
        valueOption("-coverprofile", "file")
        valueOption("-timeout", "duration")
        packagesArgument()
    }

    private fun ShellCommandContext.toolCommand() {
        displayName("tool")
        description("run specified Go tool")
        argument { displayName("tool"); suggestions("compile", "link", "vet", "test2json", "cover", "pprof") }
        argument { variadic(); displayName("arguments") }
    }

    private fun ShellCommandContext.vetCommand() {
        displayName("vet")
        description("report likely mistakes")
        valueOption("-tags", "list")
        valueOption("-vettool", "path")
        packagesArgument()
    }

    private fun ShellCommandContext.workCommand() {
        displayName("work")
        description("workspace maintenance")
        requiresSubcommand()
        subcommands {
            subcommand("edit") { moduleEdit() }
            subcommand("init") { displayName("init"); description("initialize a workspace"); argument { displayName("directory") } }
            subcommand("sync") { moduleLeaf("sync", "sync workspace build list") }
            subcommand("use") { moduleLeaf("use", "add modules to workspace") }
            subcommand("vendor") { moduleLeaf("vendor", "make a vendored copy of workspace dependencies") }
        }
    }

    private fun ShellCommandContext.commonBuildOptions() {
        flag("-a", "force rebuilding packages")
        valueOption("-o", "file")
        flag("-race", "enable race detector")
        valueOption("-gcflags", "flags")
        valueOption("-ldflags", "flags")
        valueOption("-tags", "list")
        flag("-trimpath", "remove local paths")
        modOption()
    }

    private fun ShellCommandContext.testOptions() {
        flag("-v", "verbose test output")
        flag("-short", "short test mode")
        flag("-race", "enable race detector")
        flag("-cover", "enable coverage")
        flag("-failfast", "stop after the first failure")
        flag("-json", "print test2json output")
    }

    private fun ShellCommandContext.modOption() = valueOption("-mod", "mode", "mod", "readonly", "vendor")

    private fun ShellCommandContext.packagesArgument() {
        argument { displayName("packages"); variadic(); suggestions("./...", ".") }
    }

    private fun ShellCommandContext.flag(name: String, description: String) {
        option(name) { description(description) }
    }

    private fun ShellCommandContext.valueOption(name: String, argName: String, vararg choices: String) {
        option(name) { valueArgument(argName, choices) }
    }

    private fun ShellOptionContext.valueArgument(argName: String, choices: Array<out String>) {
        argument {
            displayName(argName)
            if (choices.isNotEmpty()) suggestions(*choices)
        }
    }

}
