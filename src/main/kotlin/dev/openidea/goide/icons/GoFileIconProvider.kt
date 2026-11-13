package dev.openidea.goide.icons

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.openidea.goide.lang.GoFileType
import javax.swing.Icon

/** A test source remains the same Go file type, parser and language-server document. */
class GoFileIconProvider : FileIconProvider, DumbAware {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (!file.isDirectory && file.name.endsWith("_test.go") && file.fileType == GoFileType.INSTANCE) GoIcons.Test else null
}
