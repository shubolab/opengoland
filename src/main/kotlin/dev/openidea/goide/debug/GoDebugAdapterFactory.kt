package dev.openidea.goide.debug

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory

class GoDebugAdapterFactory : DebugAdapterDescriptorFactory() {
    override fun createDebugAdapterDescriptor(options: RunConfigurationOptions, environment: ExecutionEnvironment): DebugAdapterDescriptor =
        if (options is GoConnectionOptions) GoConnectionDescriptor(options, environment, serverDefinition,
            environment.runProfile is GoLocalAttachConfiguration)
        else GoDebugDescriptor(options, environment, serverDefinition)
    override fun isDebuggableFile(file: VirtualFile, project: Project) = file.extension == "go"
    override fun prepareConfiguration(configuration: RunConfiguration, file: VirtualFile, project: Project) = false
    override fun canRun(executorId: String) = executorId == DefaultDebugExecutor.EXECUTOR_ID
}
