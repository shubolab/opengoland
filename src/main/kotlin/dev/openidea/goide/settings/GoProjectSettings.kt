package dev.openidea.goide.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "OpenIDEAGoSettings", storages = [Storage("openidea-go.xml")])
class GoProjectSettings : PersistentStateComponent<GoProjectSettings.Data> {
    class Data {
        var goExecutable = ""
        var goplsExecutable = ""
        var delveExecutable = ""
        var buildTags = ""
        var goos = ""
        var goarch = ""
        var cgoEnabled = ""
        var environment = ""
    }
    private val data = Data()
    override fun getState() = data
    override fun loadState(state: Data) = XmlSerializerUtil.copyBean(state, data)
    companion object { fun get(project: Project): Data = project.getService(GoProjectSettings::class.java).state }
}
