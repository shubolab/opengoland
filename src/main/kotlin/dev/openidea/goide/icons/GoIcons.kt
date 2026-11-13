package dev.openidea.goide.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader

/** File-kind icons; execution, diagnostics and coverage states retain their own platform icons. */
object GoIcons {
    @JvmField val Source = IconLoader.getIcon("/icons/go.svg", GoIcons::class.java)
    @JvmField val Test = IconLoader.getIcon("/icons/goTest.svg", GoIcons::class.java)
    @JvmField val Module = IconLoader.getIcon("/icons/goMod.svg", GoIcons::class.java)
    @JvmField val Workspace = IconLoader.getIcon("/icons/goWork.svg", GoIcons::class.java)
    @JvmField val Template = AllIcons.Nodes.Template
    @JvmField val Corpus = AllIcons.FileTypes.Text
}
