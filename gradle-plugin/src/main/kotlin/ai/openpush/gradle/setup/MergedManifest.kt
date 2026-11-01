package ai.openpush.gradle.setup

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The parts of a variant's merged `AndroidManifest.xml` that push plumbing lives in. */
internal class MergedManifest(val components: List<Component>, val metaData: List<MetaData>) {
    data class Component(val tag: String, val name: String, val actions: List<String>, val enabled: Boolean)

    /** An application-level `<meta-data>`; [value] is `android:value`, or `android:resource` when that is set. */
    data class MetaData(val name: String, val value: String?, val isResource: Boolean)

    fun receiving(action: String, tag: String) = components.filter { it.tag == tag && action in it.actions }

    fun metaValue(name: String): String? = metaData.firstOrNull { it.name == name }?.value

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"

        fun read(file: File): MergedManifest {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val root = factory.newDocumentBuilder().parse(file).documentElement
            val application = root.children("application").firstOrNull()
                ?: return MergedManifest(emptyList(), emptyList())
            val packageName = root.getAttribute("package")
            val components = application.children().filter { it.localName in COMPONENT_TAGS }.map { element ->
                Component(
                    tag = element.localName,
                    name = qualify(element.getAttributeNS(ANDROID, "name"), packageName),
                    actions = element.children("intent-filter")
                        .flatMap { it.children("action") }
                        .map { it.getAttributeNS(ANDROID, "name") },
                    enabled = element.getAttributeNS(ANDROID, "enabled") != "false",
                )
            }
            val metaData = application.children("meta-data").map { element ->
                val resource = element.getAttributeNS(ANDROID, "resource").takeIf { it.isNotEmpty() }
                MetaData(
                    name = element.getAttributeNS(ANDROID, "name"),
                    value = resource ?: element.getAttributeNS(ANDROID, "value").takeIf { it.isNotEmpty() },
                    isResource = resource != null,
                )
            }
            return MergedManifest(components, metaData)
        }

        private val COMPONENT_TAGS = setOf("service", "receiver", "activity", "provider")

        private fun qualify(name: String, packageName: String) =
            if (name.startsWith(".") && packageName.isNotEmpty()) packageName + name else name

        private fun Element.children(tag: String? = null): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                .filter { tag == null || it.localName == tag }
        }
    }
}
