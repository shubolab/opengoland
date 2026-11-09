package dev.openidea.goide.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "OpenIDEAGoSettings", storages = [Storage("openidea-go.xml")])
class GoProjectSettings(private val project: Project) : PersistentStateComponent<GoProjectSettings.Data> {
    class Data {
        var goExecutable = ""
        var goplsExecutable = ""
        var delveExecutable = ""
        var buildTags = ""
        var goos = ""
        var goarch = ""
        var cgoEnabled = ""
        var environment = ""
        var indentRawStringsOnEnter = false
    }
    private val data = Data()
    override fun getState() = data
    override fun loadState(state: Data) = XmlSerializerUtil.copyBean(state, data)
    override fun initializeComponent() {
        // The platform calls this once, after persistent/template state has been
        // loaded and before the service is returned to its first consumer.
        val initial = project.getUserData(INITIAL_SELECTION) ?: return
        data.goExecutable = initial.executable
        data.environment = initial.environment
        project.putUserData(INITIAL_SELECTION, null)
    }

    companion object {
        private data class InitialSelection(val executable: String, val environment: String)
        private val INITIAL_SELECTION = Key.create<InitialSelection>("open Goland initial project SDK")

        /** beforeInit runs before project services can safely be instantiated. */
        internal fun stageNewProject(project: Project, executable: String, environment: String) {
            project.putUserData(INITIAL_SELECTION, InitialSelection(executable, environment))
        }

        fun get(project: Project): Data = project.getService(GoProjectSettings::class.java).state
    }
}
