package dev.openidea.goide.http

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/** A native Services extension, not a second tool window with a copied tree. */
internal class HttpServicesContributor : ServiceViewContributor<HttpRequestSession>, DumbAware {
    override fun getServices(project: Project): List<HttpRequestSession> =
        if (project.isDisposed) emptyList() else project.getService(HttpRequestRuns::class.java).sessions()

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = object : ServiceViewDescriptor {
        override fun getId() = "open.goland.http.requests"
        override fun getPresentation() = PresentationData("HTTP Requests", null, AllIcons.FileTypes.Http, null)
    }

    override fun getServiceDescriptor(project: Project, service: HttpRequestSession): ServiceViewDescriptor = service.descriptor
}
