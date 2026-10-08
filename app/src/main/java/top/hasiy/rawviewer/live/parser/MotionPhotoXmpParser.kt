/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.parser

import android.util.Log
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

data class MotionPhotoDescriptor(
    val videoLength: Long,
    val presentationTimestampUs: Long?,
    val version: Int?,
    val isLegacy: Boolean,
    val evidence: List<String>,
)

object MotionPhotoXmpParser {

    fun parse(xmp: String): MotionPhotoDescriptor? {
        if (xmp.contains(DOCTYPE_MARKER, ignoreCase = true)) return null
        val root = parseDocument(xmp) ?: return parseLegacyWithRegex(xmp)
        val elements = root.getElementsByTagName("*")
        val cameraAttributes = mutableMapOf<String, String>()
        var videoLength: Long? = null

        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            collectAttributes(element, cameraAttributes)
            val semantic = element.attributeByLocalName("Semantic")
            val mime = element.attributeByLocalName("Mime")
            if (semantic == MOTION_PHOTO_SEMANTIC && mime in VIDEO_MIME_TYPES) {
                videoLength = element.attributeByLocalName("Length")?.toLongOrNull()
            }
        }

        val motionPhoto = cameraAttributes["MotionPhoto"]?.toIntOrNull()
        if (motionPhoto == 1 && videoLength != null && videoLength > 0L) {
            return MotionPhotoDescriptor(
                videoLength = videoLength,
                presentationTimestampUs = cameraAttributes["MotionPhotoPresentationTimestampUs"]
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L },
                version = cameraAttributes["MotionPhotoVersion"]?.toIntOrNull(),
                isLegacy = false,
                evidence = listOf("Camera:MotionPhoto=1", "Container:Item Semantic=MotionPhoto"),
            )
        }

        val microVideo = cameraAttributes["MicroVideo"]?.toIntOrNull()
        val microVideoOffset = cameraAttributes["MicroVideoOffset"]?.toLongOrNull()
        if (microVideo == 1 && microVideoOffset != null && microVideoOffset > 0L) {
            return MotionPhotoDescriptor(
                videoLength = microVideoOffset,
                presentationTimestampUs = cameraAttributes["MicroVideoPresentationTimestampUs"]
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L },
                version = cameraAttributes["MicroVideoVersion"]?.toIntOrNull(),
                isLegacy = true,
                evidence = listOf("GCamera:MicroVideo=1", "GCamera:MicroVideoOffset"),
            )
        }
        return null
    }

    private fun parseDocument(xmp: String): Element? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        setFeatureIfSupported(
            factory,
            "http://apache.org/xml/features/disallow-doctype-decl",
            true,
        )
        setFeatureIfSupported(
            factory,
            "http://xml.org/sax/features/external-general-entities",
            false,
        )
        setFeatureIfSupported(
            factory,
            "http://xml.org/sax/features/external-parameter-entities",
            false,
        )
        factory.newDocumentBuilder().parse(InputSource(StringReader(xmp))).documentElement
    } catch (error: Exception) {
        Log.w(TAG, "Unable to parse XMP packet: ${error.javaClass.simpleName}")
        null
    }

    private fun setFeatureIfSupported(
        factory: DocumentBuilderFactory,
        feature: String,
        enabled: Boolean,
    ) {
        try {
            factory.setFeature(feature, enabled)
        } catch (error: ParserConfigurationException) {
            Log.w(TAG, "XML parser feature unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun collectAttributes(element: Element, destination: MutableMap<String, String>) {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            destination[attribute.localNameOrSuffix()] = attribute.nodeValue
        }
    }

    private fun Element.attributeByLocalName(name: String): String? {
        val attributes = attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.localNameOrSuffix() == name) return attribute.nodeValue
        }
        return null
    }

    private fun Node.localNameOrSuffix(): String = localName ?: nodeName.substringAfter(':')

    private fun parseLegacyWithRegex(xmp: String): MotionPhotoDescriptor? {
        val enabled = findNumericAttribute(xmp, "MicroVideo")?.toIntOrNull()
        val offset = findNumericAttribute(xmp, "MicroVideoOffset")?.toLongOrNull()
        if (enabled != 1 || offset == null || offset <= 0L) return null
        return MotionPhotoDescriptor(
            videoLength = offset,
            presentationTimestampUs = findNumericAttribute(
                xmp,
                "MicroVideoPresentationTimestampUs",
            )?.toLongOrNull()?.takeIf { it >= 0L },
            version = findNumericAttribute(xmp, "MicroVideoVersion")?.toIntOrNull(),
            isLegacy = true,
            evidence = listOf("GCamera:MicroVideo=1", "GCamera:MicroVideoOffset"),
        )
    }

    private fun findNumericAttribute(xmp: String, localName: String): String? {
        val expression = Regex(
            pattern = "(?:[A-Za-z_][A-Za-z0-9_.-]*:)?${Regex.escape(localName)}\\s*=\\s*[\"'](-?\\d+)[\"']",
        )
        return expression.find(xmp)?.groupValues?.get(1)
    }

    private const val MOTION_PHOTO_SEMANTIC = "MotionPhoto"
    private const val DOCTYPE_MARKER = "<!DOCTYPE"
    private const val TAG = "MotionPhotoXmpParser"
    private val VIDEO_MIME_TYPES = setOf("video/mp4", "video/quicktime")
}
