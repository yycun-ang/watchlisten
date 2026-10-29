package com.watchlisten.music.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

/** 网络 XML 只允许普通元素与预定义实体；拒绝 DTD、外部实体和过深文档。 */
object DlnaXml {
    const val MAX_BODY_BYTES = 256 * 1024
    const val MAX_METADATA_BYTES = 64 * 1024
    private val declarations = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)

    fun parse(text: String, maxBytes: Int = MAX_BODY_BYTES): Document {
        if (text.length > maxBytes || text.toByteArray(Charsets.UTF_8).size > maxBytes || declarations.containsMatchIn(text))
            throw DlnaFault(402, "Invalid Args")
        try {
            val sax = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }.newSAXParser().xmlReader
            val handler = object : DefaultHandler() {
                private var depth = 0
                private var elements = 0
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
                    if (++depth > 32 || ++elements > 4096) throw SAXException("XML limit")
                }
                override fun endElement(uri: String?, localName: String?, qName: String?) { depth-- }
                override fun error(error: org.xml.sax.SAXParseException) { throw error }
                override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
            }
            sax.contentHandler = handler
            sax.errorHandler = handler
            sax.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            sax.parse(InputSource(StringReader(text)))
            val builder = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { isXIncludeAware = false }
                isExpandEntityReferences = false
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.setErrorHandler(handler)
            return builder.parse(InputSource(StringReader(text)))
        } catch (failure: DlnaFault) { throw failure }
        catch (_: Exception) { throw DlnaFault(402, "Invalid Args") }
    }

    fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }

    fun escape(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val value = text.codePointAt(index)
            index += Character.charCount(value)
            when (value) {
                38 -> append("&amp;")
                60 -> append("&lt;")
                62 -> append("&gt;")
                34 -> append("&quot;")
                39 -> append("&apos;")
                else -> if (value == 9 || value == 10 || value == 13 || value in 0x20..0xd7ff ||
                    value in 0xe000..0xfffd || value in 0x10000..0x10ffff) appendCodePoint(value)
            }
        }
    }
}
