package dev.openidea.goide.plugins

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.labels.LinkLabel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextArea

internal class ChineseLanguagePackDialog(
    private val project: Project?,
    private val source: ChinesePackSource,
    private val actualBuild: String,
    private val preparation: ChinesePackPreparation = ChinesePackPreparer(),
    private val installer: ChinesePackInstaller = NativeChinesePackInstaller(),
    private val owner: Disposable = project ?: ApplicationManager.getApplication(),
) : DialogWrapper(project) {
    private val download = JBRadioButton("从 JetBrains 官方获取（约 3.6 MB）", true)
    private val local = JBRadioButton("使用已下载的官方安装包（.zip，约 1.6 GB）")
    private val file = JBTextField().apply { isEditable = false }
    private val browse = JButton("选择 ZIP…")
    private val status = JTextArea(3, 58).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
    private val progress = JProgressBar(0, 1000)
    private val ownerClosed = AtomicBoolean()
    private val pending = AtomicReference<PreparedChinesePack?>()
    private var cancellation = ChinesePackCancellation()
    private var busy = false
    private var generation = 0L
    private var lastProgress = 0L
    @Volatile private var uiModality = ModalityState.current()

    init {
        title = "安装简体中文语言包"
        setOKButtonText("继续安装")
        setCancelButtonText("取消")
        init()
        uiModality = ModalityState.stateForComponent(download)
        ButtonGroup().apply { add(download); add(local) }
        download.addActionListener { updateEnabled() }
        local.addActionListener { updateEnabled() }
        browse.addActionListener {
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("选择官方 IntelliJ IDEA 安装包").withExtensionFilter("官方 ZIP 安装包", "zip"), project, null) {
                file.text = it.toNioPath().toString()
                updateEnabled()
            }
        }
        Disposer.register(disposable, Disposable { cancellation.cancel(); pending.getAndSet(null)?.close() })
        val lifetime = Disposable {
            ownerClosed.set(true)
            cancellation.cancel()
            pending.getAndSet(null)?.close()
            ApplicationManager.getApplication().invokeLater({ if (!isDisposed) close(CANCEL_EXIT_CODE) }, uiModality)
        }
        if (!Disposer.tryRegister(owner, lifetime)) lifetime.dispose()
        else Disposer.register(disposable, Disposable { Disposer.dispose(lifetime) })
        status.text = if (actualBuild == source.platformBuild)
            "将从 JetBrains 官方获取与当前平台匹配的语言包。"
            else "此入口适用于平台版本 " + source.platformBuild + "，当前为 $actualBuild。"
        updateEnabled()
    }

    override fun createCenterPanel(): JComponent {
        val choices = JPanel(GridLayout(0, 1, 0, 6)).apply {
            add(JBLabel("适用平台版本：" + source.platformBuild))
            add(download); add(local)
            add(JPanel(BorderLayout(8, 0)).apply { add(file, BorderLayout.CENTER); add(browse, BorderLayout.EAST) })
        }
        val information = JTextArea(
            "下一步会打开原生插件安装窗口，请选中 localization-zh.zip 并确认安装。\n" +
                "安装完成后请重新打开 IDE，再到“设置 → 语言和区域 / Language and Region”选择简体中文。"
        ).apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
        val links = JPanel().apply {
            add(LinkLabel.create("官方来源") { browse(source.sourcePage.toString()) })
            add(LinkLabel.create("许可条款") { browse(source.licensePage.toString()) })
        }
        return JPanel(BorderLayout(0, 10)).apply {
            add(choices, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 8)).apply { add(information, BorderLayout.CENTER); add(links, BorderLayout.SOUTH) }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, 5)).apply { add(progress, BorderLayout.NORTH); add(status, BorderLayout.CENTER) }, BorderLayout.SOUTH)
            preferredSize = Dimension(640, 300)
        }
    }

    private fun browse(url: String) { ApplicationManager.getApplication().executeOnPooledThread { BrowserUtil.browse(url) } }

    override fun doValidate(): ValidationInfo? = when {
        actualBuild != source.platformBuild -> ValidationInfo("当前平台版本暂无匹配的语言包。")
        busy -> ValidationInfo("请等待当前操作完成，或点击取消。")
        local.isSelected && file.text.isBlank() -> ValidationInfo("请选择完整的官方 ZIP 安装包。", file)
        else -> null
    }

    override fun doOKAction() {
        if (doValidate() != null || ownerClosed.get() || project?.isDisposed == true) return
        val input = if (local.isSelected) Path.of(file.text) else null
        cancellation.cancel(); cancellation = ChinesePackCancellation()
        val token = cancellation
        // Capture the active dialog modality on the EDT. The native installer
        // may update PSI/indexes; ANY would discard TransactionGuard safety.
        val modality = ModalityState.current()
        uiModality = modality
        val ticket = ++generation
        busy = true; progress.isIndeterminate = true; status.text = "正在准备语言包…"; updateEnabled()
        ApplicationManager.getApplication().executeOnPooledThread {
            var ready: PreparedChinesePack? = null
            try {
                ready = preparation.prepare(source, input, actualBuild, token) { value ->
                    val now = System.nanoTime()
                    if (value.total == null || value.completed == value.total || now - lastProgress > 100_000_000) {
                        lastProgress = now
                        deliver(ticket, token, modality) {
                            status.text = value.message; progress.isIndeterminate = value.total == null
                            value.total?.takeIf { it > 0 }?.let { progress.value = (value.completed * 1000 / it).toInt().coerceIn(0, 1000) }
                        }
                    }
                }
                val prepared = ready
                pending.set(prepared)
                token.check()
                val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(prepared.zip)
                    ?: throw IllegalStateException("准备好的语言包文件不可用，请重试。")
                token.check()
                ApplicationManager.getApplication().invokeLater({
                    if (!valid(ticket, token) || !pending.compareAndSet(prepared, null)) {
                        pending.compareAndSet(prepared, null); prepared.close()
                    } else if (prepared.retainForNativeInstaller()) {
                        // Native startup commands are queued before its callback;
                        // retain even if a later dialog, listener or callback fails.
                        close(OK_EXIT_CODE)
                        try { installer.install(project, virtual, prepared, source) }
                        catch (error: Exception) {
                            if (error !is ProcessCanceledException && error !is CancellationException &&
                                !ApplicationManager.getApplication().isDisposed && project?.isDisposed != true && !Disposer.isDisposed(owner))
                                Messages.showErrorDialog(project, error.message ?: "原生插件安装未完成，请重试。", "中文语言包")
                        } finally { prepared.close() }
                    }
                }, modality)
            } catch (error: Exception) {
                ready?.let { pending.compareAndSet(it, null); it.close() }
                if (!token.isCancelled()) deliver(ticket, token, modality) {
                    busy = false; progress.isIndeterminate = false; progress.value = 0
                    status.text = if (error is ProcessCanceledException || error is CancellationException) "已取消语言包准备。"
                        else error.message ?: "语言包准备失败，请检查网络后重试。"
                    updateEnabled()
                }
            }
        }
    }

    override fun doCancelAction() { cancellation.cancel(); super.doCancelAction() }
    private fun valid(ticket: Long, token: ChinesePackCancellation) =
        !isDisposed && !ownerClosed.get() && project?.isDisposed != true && generation == ticket && !token.isCancelled()

    private fun deliver(ticket: Long, token: ChinesePackCancellation, modality: ModalityState, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (valid(ticket, token)) action() }, modality)
    }

    private fun updateEnabled() {
        download.isEnabled = !busy; local.isEnabled = !busy
        file.isEnabled = !busy && local.isSelected; browse.isEnabled = file.isEnabled
        setOKActionEnabled(!busy && actualBuild == source.platformBuild && (!local.isSelected || file.text.isNotBlank()))
    }
}
