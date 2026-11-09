package dev.openidea.goide.refactor.native

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.openidea.goide.refactor.signature.array
import dev.openidea.goide.refactor.signature.bool
import dev.openidea.goide.refactor.signature.string
import dev.openidea.goide.semantic.GoIdentifiers
import java.awt.BorderLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

/** Names and declaration disposition are chosen before the immutable plan is applied. */
internal class GoNativeRefactorDialog(project: Project, private val inspection: JsonObject,
                                     private val kind: String, draft: JsonObject? = null) : DialogWrapper(project) {
    private val rows = inspection.array("names").map { entry ->
        val value = entry.asJsonObject
        val saved = draft?.array("names")?.firstOrNull { it.asJsonObject.string("nameId") == value.string("nameId") }?.asJsonObject
        value to JBTextField(saved?.string("name") ?: value.string("suggestedName"))
    }
    private val inline = inspection.getAsJsonObject("inline")
    private val keep = JRadioButton("Keep the original declaration", inline?.bool("canKeep") == true)
    private val remove = JRadioButton("Remove the now-unused declaration", inline?.bool("canKeep") != true && inline?.bool("canRemove") == true)
    init {
        title = titleFor(kind)
        setOKButtonText("Preview")
        ButtonGroup().apply { add(keep); add(remove) }
        keep.isEnabled = inline?.bool("canKeep") == true
        remove.isEnabled = inline?.bool("canRemove") == true
        draft?.string("inlineDeclaration")?.let { value ->
            if (value == "keep" && keep.isEnabled) keep.isSelected = true
            if (value == "remove" && remove.isEnabled) remove.isSelected = true
        }
        init()
    }
    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
        rows.forEach { (name, field) -> form.addLabeledComponent("${name.string("type")}  — name:", field) }
        val signature = inspection.string("signature")
        if (signature.isNotBlank()) form.addComponent(JBTextArea(signature, 3, 56).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false })
        if (inline != null) {
            val scope = if (inline.bool("all")) "all supported workspace occurrences" else "this selected occurrence"
            form.addComponent(JBLabel("Inline $scope of ${inline.string("name")}; ${inline.get("references")?.asInt ?: 0} reference(s)."))
                .addComponent(keep).addComponent(remove)
            inline.string("removalReason").takeIf(String::isNotBlank)?.let { text ->
                form.addComponent(JBTextArea(text, 2, 56).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false })
            }
        } else form.addComponent(JBLabel("${inspection.get("occurrences")?.asInt ?: 0} replacement occurrence(s); conflicts are checked before preview."))
        return JPanel(BorderLayout()).apply { add(form.panel, BorderLayout.CENTER) }
    }
    override fun getPreferredFocusedComponent(): JComponent? = rows.firstOrNull()?.second ?: if (keep.isEnabled) keep else remove
    override fun doValidate(): ValidationInfo? {
        val seen = mutableSetOf<String>()
        rows.forEach { (_, field) ->
            val name = field.text.trim()
            if (!GoIdentifiers.valid(name)) return ValidationInfo("Choose a valid non-blank Go name", field)
            if (!seen.add(name)) return ValidationInfo("Generated names must be distinct", field)
        }
        if (inline != null && !keep.isSelected && !remove.isSelected) return ValidationInfo("Choose a supported declaration disposition")
        return null
    }
    fun selection() = JsonObject().apply {
        addProperty("inspectionId", inspection.string("inspectionId"))
        addProperty("configurationDigest", inspection.string("configurationDigest"))
        add("names", JsonArray().apply { rows.forEach { (row, field) -> add(JsonObject().apply { addProperty("nameId", row.string("nameId")); addProperty("name", field.text.trim()) }) } })
        if (inline != null) addProperty("inlineDeclaration", if (remove.isSelected) "remove" else "keep")
    }
    companion object {
        fun titleFor(kind: String) = when {
            kind.startsWith("extract.variable") -> "Extract Go Variable"
            kind.startsWith("extract.constant") -> "Extract Go Constant"
            kind == "extract.function" -> "Extract Go Function"
            kind == "extract.method" -> "Extract Go Method"
            kind.startsWith("inline.variable") -> "Inline Go Variable"
            else -> "Inline Go Call"
        }
    }
}
