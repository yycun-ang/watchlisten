package com.watchlisten.music

import com.watchlisten.music.dlna.DlnaCommand
import com.watchlisten.music.dlna.DlnaFault
import com.watchlisten.music.dlna.DlnaIdentity
import com.watchlisten.music.dlna.DlnaMedia
import com.watchlisten.music.dlna.DlnaProtocol
import com.watchlisten.music.dlna.DlnaService
import com.watchlisten.music.dlna.DlnaSnapshot
import com.watchlisten.music.dlna.DlnaTarget
import com.watchlisten.music.dlna.DlnaXml
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class DlnaProtocolTest {
    private val target = RecordingTarget()
    private val protocol = DlnaProtocol(DlnaIdentity("abc-123", "存存 & 音乐 🎵"), target)

    @Test fun deviceDescriptionNamesTheRendererAndUsesReachableServicePaths() {
        val document = xml(protocol.description("http://192.168.1.8:19492"))
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", text(document, "deviceType"))
        assertEquals("存存 & 音乐 🎵", text(document, "friendlyName"))
        assertEquals("uuid:abc-123", text(document, "UDN"))
        assertEquals("http://192.168.1.8:19492/", text(document, "URLBase"))
        val services = nodes(document, "service")
        assertEquals(3, services.size)
        DlnaService.entries.forEach { service ->
            val node = services.single { childText(it, "serviceType") == service.urn }
            assertEquals("/${service.path}.xml", childText(node, "SCPDURL"))
            assertEquals("/${service.path}/control", childText(node, "controlURL"))
            assertEquals("/${service.path}/event", childText(node, "eventSubURL"))
        }
    }

    @Test fun descriptionRejectsAmbiguousOrCredentialedBaseUrls() {
        listOf("file:///secret", "http://user:pass@192.168.1.2", "http://192.168.1.2/wrong", "http://192.168.1.2/?secret=1").forEach {
            assertThrows(IllegalArgumentException::class.java) { protocol.description(it) }
        }
        val prefixed = DlnaProtocol(DlnaIdentity("uuid:already", "手表"), target)
        assertEquals("uuid:already", text(xml(prefixed.description("http://192.168.1.2/")), "UDN"))
    }

    @Test fun serviceDescriptionsDeclareEveryArgumentAndStateVariable() {
        val expectedCounts = mapOf(DlnaService.TRANSPORT to 15, DlnaService.RENDERING to 4, DlnaService.CONNECTION to 3)
        expectedCounts.forEach { (service, count) ->
            val document = xml(protocol.serviceDescription(service))
            assertEquals("urn:schemas-upnp-org:service-1-0", document.documentElement.namespaceURI)
            val stateNames = nodes(document, "stateVariable").map { childText(it, "name") }.toSet()
            val actions = nodes(document, "action")
            assertEquals(count, actions.size)
            assertEquals(count, actions.map { childText(it, "name") }.toSet().size)
            nodes(document, "argument").forEach { argument ->
                assertTrue(childText(argument, "direction") in setOf("in", "out"))
                assertTrue("Missing related state for $service", childText(argument, "relatedStateVariable") in stateNames)
            }
            val eventNames = nodes(document, "stateVariable").filter { it.getAttribute("sendEvents") == "yes" }
                .map { childText(it, "name") }.toSet()
            assertEquals(if (service == DlnaService.CONNECTION) setOf("SourceProtocolInfo", "SinkProtocolInfo", "CurrentConnectionIDs") else setOf("LastChange"), eventNames)
        }
    }

    @Test fun uriMetadataIncludesArtworkArtistDurationAndMatchingResourceMime() {
        val uri = "http://192.168.1.3/music.flac?a=1&b=2"
        val metadata = didl("<dc:title>月亮 &amp; 星星 🎵</dc:title><upnp:artist>歌手甲</upnp:artist>" +
            "<upnp:album>专辑乙</upnp:album><upnp:albumArtURI>http://192.168.1.3/cover?a=1&amp;b=2</upnp:albumArtURI>" +
            "<res protocolInfo=\"http-get:*:audio/mpeg:*\" duration=\"0:01:00\">http://192.168.1.3/other.mp3</res>" +
            "<res protocolInfo=\"http-get:*:audio/flac:*\" duration=\"1:02:03.45\">${escape(uri)}</res>")
        val response = call("SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
        assertEquals("SetAVTransportURIResponse", responseAction(response).localName)
        val command = target.commands.single().second as DlnaCommand.SetUri
        assertEquals("192.168.1.3", target.commands.single().first)
        assertEquals(DlnaMedia(uri, metadata, "月亮 & 星星 🎵", "歌手甲", "专辑乙", "http://192.168.1.3/cover?a=1&b=2", 3_723_450, "audio/flac"), command.media)
        assertFalse(command.next)
    }

    @Test fun nextUriUsesTheSameMetadataParsingWithoutReplacingCurrentTrack() {
        val metadata = didl("<dc:title>下一首</dc:title><dc:creator>创作者</dc:creator>")
        call("SetNextAVTransportURI", "InstanceID" to "0", "NextURI" to "http://192.168.1.3/next.aac", "NextURIMetaData" to metadata)
        val command = target.commands.single().second as DlnaCommand.SetUri
        assertTrue(command.next)
        assertEquals("下一首", command.media.title)
        assertEquals("创作者", command.media.artist)
        assertEquals(0L, command.media.durationMs)
    }

    @Test fun emptyUriAndEmptyMetadataReachTheTargetToClearTheSession() {
        call("SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to "", "CurrentURIMetaData" to "")
        assertEquals(DlnaCommand.SetUri(DlnaMedia("")), target.commands.single().second)
    }

    @Test fun oversizedMetadataAndControlCharactersCannotReachThePlayer() {
        fault(402) { call("SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to "http://host/\nfile", "CurrentURIMetaData" to "") }
        fault(402) { call("SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to "http://host/file", "CurrentURIMetaData" to "字".repeat(22_000)) }
        assertTrue(target.commands.isEmpty())
    }

    @Test fun malformedDidlAndNonAudioMediaAreRejected() {
        listOf("<broken", "<item/>", "<DIDL-Lite xmlns=\"wrong\"><item/></DIDL-Lite>").forEach { metadata ->
            fault(402) { call("SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to "http://host/file", "CurrentURIMetaData" to metadata) }
        }
        fault(714) { call("SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to "http://host/video", "CurrentURIMetaData" to didl("<res protocolInfo=\"http-get:*:video/mp4:*\">http://host/video</res>")) }
        assertTrue(target.commands.isEmpty())
    }

    @Test fun allPlaybackMutationsUseTheExplicitCommandWhitelist() {
        listOf("Play" to DlnaCommand.Play, "Pause" to DlnaCommand.Pause, "Stop" to DlnaCommand.Stop,
            "Next" to DlnaCommand.Next, "Previous" to DlnaCommand.Previous).forEach { (name, command) ->
            val args = if (name == "Play") arrayOf("InstanceID" to "0", "Speed" to "1") else arrayOf("InstanceID" to "0")
            val response = call(name, *args)
            assertEquals("${name}Response", responseAction(response).localName)
            assertEquals(command, target.commands.last().second)
        }
        fault(401) { call("snapshot", "InstanceID" to "0") }
        fault(717) { call("Play", "InstanceID" to "0", "Speed" to "2") }
        assertEquals(5, target.commands.size)
    }

    @Test fun seekAcceptsBothSupportedUnitsAndRejectsInvalidTimes() {
        listOf("REL_TIME", "ABS_TIME").forEach { unit ->
            call("Seek", "InstanceID" to "0", "Unit" to unit, "Target" to "0:02:03.125")
            assertEquals(DlnaCommand.Seek(123_125), target.commands.last().second)
        }
        fault(710) { call("Seek", "InstanceID" to "0", "Unit" to "TRACK_NR", "Target" to "2") }
        listOf("-0:01:00", "0:60:00", "0:00:99", "123", "NaN", "0:00:00.0001").forEach {
            fault(711) { call("Seek", "InstanceID" to "0", "Unit" to "REL_TIME", "Target" to it) }
        }
        assertEquals(2, target.commands.size)
    }

    @Test fun playModeIsValidatedAndReportedFromThePlayer() {
        call("SetPlayMode", "InstanceID" to "0", "NewPlayMode" to "REPEAT_ONE")
        assertEquals(DlnaCommand.PlayMode("REPEAT_ONE"), target.commands.single().second)
        fault(712) { call("SetPlayMode", "InstanceID" to "0", "NewPlayMode" to "ARBITRARY") }
        target.state = DlnaSnapshot(playMode = "SHUFFLE")
        assertEquals("SHUFFLE", text(xml(call("GetTransportSettings", "InstanceID" to "0")), "PlayMode"))
    }

    @Test fun instanceIdsAndArgumentSetsAreStrictlyValidated() {
        listOf("1", "-1", "garbage", "999999999999999999999").forEach {
            fault(718) { call("Pause", "InstanceID" to it) }
        }
        fault(402) { call("Pause") }
        fault(402) { call("Pause", "InstanceID" to "0", "Unexpected" to "1") }
        fault(402) { call("Pause", "InstanceID" to "0", "InstanceID" to "0") }
        fault(402) { raw(DlnaService.TRANSPORT, "Pause", soap(DlnaService.TRANSPORT, "Pause", "<InstanceID><value>0</value></InstanceID>")) }
        assertTrue(target.commands.isEmpty())
    }

    @Test fun soapHeaderActionAndBodyNamespaceMustAgree() {
        fault(401) { raw(DlnaService.TRANSPORT, "Play", soap(DlnaService.TRANSPORT, "Pause", "<InstanceID>0</InstanceID>")) }
        fault(401) { raw(DlnaService.TRANSPORT, "Pause", soap(DlnaService.RENDERING, "Pause", "<InstanceID>0</InstanceID>")) }
        fault(401) { protocol.control(DlnaService.TRANSPORT, "${DlnaService.RENDERING.urn}#Pause", soap(DlnaService.TRANSPORT, "Pause", "<InstanceID>0</InstanceID>"), "192.168.1.3") }
        fault(402) { raw(DlnaService.TRANSPORT, "Pause", "<Envelope><Body/></Envelope>") }
        fault(401) { raw(DlnaService.TRANSPORT, "Pause", soap(DlnaService.TRANSPORT, "Pause", "<InstanceID>0</InstanceID>").replace("</s:Body>", "<u:Stop xmlns:u=\"${DlnaService.TRANSPORT.urn}\"/></s:Body>")) }
        assertTrue(target.commands.isEmpty())
    }

    @Test fun dtdAndExternalEntitiesAreRejectedBeforeAnyTargetAccess() {
        val ordinary = soap(DlnaService.TRANSPORT, "Pause", "<InstanceID>0</InstanceID>")
        listOf("<!DOCTYPE s:Envelope [<!ENTITY secret SYSTEM \"file:///secret\">]>",
            "<!DOCTYPE s:Envelope SYSTEM \"http://192.168.1.1/secret\">", "<!ENTITY secret \"test\">").forEach { declaration ->
            fault(402) { raw(DlnaService.TRANSPORT, "Pause", declaration + ordinary) }
        }
        val malicious = "<!DOCTYPE DIDL-Lite [<!ENTITY secret SYSTEM \"file:///secret\">]>" + didl("<dc:title>&secret;</dc:title>")
        fault(402) { call("SetAVTransportURI", "InstanceID" to "0", "CurrentURI" to "http://host/file", "CurrentURIMetaData" to malicious) }
        assertTrue(target.commands.isEmpty())
        assertTrue(target.snapshotPeers.isEmpty())
    }

    @Test fun xmlByteDepthAndElementLimitsRejectResourceExhaustion() {
        fault(402) { DlnaXml.parse("<root>${"字".repeat(90_000)}</root>") }
        fault(402) { DlnaXml.parse("<a>".repeat(33) + "</a>".repeat(33)) }
        fault(402) { DlnaXml.parse("<root>" + "<a/>".repeat(4096) + "</root>") }
        fault(402) { raw(DlnaService.TRANSPORT, "Pause", "x".repeat(DlnaXml.MAX_BODY_BYTES + 1)) }
    }

    @Test fun positionUsesTheLiveTimelineAndPreservesEscapedMetadata() {
        val metadata = didl("<dc:title>旧标题 &amp; 新标题</dc:title>")
        target.state = DlnaSnapshot(DlnaMedia("http://host/song?a=1&b=2", metadata, durationMs = 999_000),
            transportState = "PLAYING", positionMs = 65_432, durationMs = 245_000)
        val document = xml(call("GetPositionInfo", "InstanceID" to "0"))
        assertEquals("0:04:05", text(document, "TrackDuration"))
        assertEquals("0:01:05", text(document, "RelTime"))
        assertEquals("0:01:05", text(document, "AbsTime"))
        assertEquals(metadata, text(document, "TrackMetaData"))
        assertEquals("http://host/song?a=1&b=2", text(document, "TrackURI"))
        assertEquals(listOf("192.168.1.3"), target.snapshotPeers)
    }

    @Test fun mediaInfoContainsBothCurrentAndNextTracks() {
        target.state = DlnaSnapshot(DlnaMedia("http://host/one", "one"), DlnaMedia("http://host/two", "two"), durationMs = 42_000)
        val document = xml(call("GetMediaInfo", "InstanceID" to "0"))
        assertEquals("1", text(document, "NrTracks"))
        assertEquals("0:00:42", text(document, "MediaDuration"))
        assertEquals("http://host/one", text(document, "CurrentURI"))
        assertEquals("http://host/two", text(document, "NextURI"))
        assertEquals("two", text(document, "NextURIMetaData"))
        assertEquals("NETWORK", text(document, "PlayMedium"))
    }

    @Test fun transportErrorsAndCurrentActionsReflectActualCapabilities() {
        target.state = DlnaSnapshot(DlnaMedia("http://host/one"), transportState = "PLAYING", failed = true, canSeek = true, canPrevious = true)
        val info = xml(call("GetTransportInfo", "InstanceID" to "0"))
        assertEquals("PLAYING", text(info, "CurrentTransportState"))
        assertEquals("ERROR_OCCURRED", text(info, "CurrentTransportStatus"))
        val actions = text(xml(call("GetCurrentTransportActions", "InstanceID" to "0")), "Actions").split(',').toSet()
        assertEquals(setOf("Pause", "Stop", "Seek", "Previous"), actions)
        target.state = DlnaSnapshot()
        assertEquals("", text(xml(call("GetCurrentTransportActions", "InstanceID" to "0")), "Actions"))
        assertEquals("0", text(xml(call("GetMediaInfo", "InstanceID" to "0")), "NrTracks"))
    }

    @Test fun volumeAndMuteControlsUseMasterChannelAndValidateValues() {
        listOf("0", "42", "100").forEach {
            render("SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to it)
            assertEquals(DlnaCommand.Volume(it.toInt()), target.commands.last().second)
        }
        listOf("-1", "101", "1.5", "many").forEach { value ->
            fault(402) { render("SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to value) }
        }
        render("SetMute", "InstanceID" to "0", "Channel" to "Master", "DesiredMute" to "true")
        assertEquals(DlnaCommand.Mute(true), target.commands.last().second)
        render("SetMute", "InstanceID" to "0", "Channel" to "Master", "DesiredMute" to "0")
        assertEquals(DlnaCommand.Mute(false), target.commands.last().second)
        fault(402) { render("SetMute", "InstanceID" to "0", "Channel" to "Master", "DesiredMute" to "2") }
        fault(402) { render("GetVolume", "InstanceID" to "0", "Channel" to "LF") }
    }

    @Test fun volumeAndMuteQueriesReadThePlayer() {
        target.state = DlnaSnapshot(volume = 61, muted = true)
        assertEquals("61", text(xml(render("GetVolume", "InstanceID" to "0", "Channel" to "Master")), "CurrentVolume"))
        assertEquals("1", text(xml(render("GetMute", "InstanceID" to "0", "Channel" to "Master")), "CurrentMute"))
    }

    @Test fun connectionManagerAdvertisesSupportedAudioFormatsAndRealConnectionZero() {
        val sink = text(xml(connect("GetProtocolInfo")), "Sink")
        listOf("audio/mpeg", "audio/flac", "audio/aac", "audio/mp4", "audio/wav").forEach { assertTrue(sink.contains(":$it:")) }
        assertEquals("0", text(xml(connect("GetCurrentConnectionIDs")), "ConnectionIDs"))
        val connection = xml(connect("GetCurrentConnectionInfo", "ConnectionID" to "0"))
        assertEquals("Input", text(connection, "Direction"))
        assertEquals("0", text(connection, "AVTransportID"))
        assertEquals("0", text(connection, "RcsID"))
        assertEquals("OK", text(connection, "Status"))
        fault(706) { connect("GetCurrentConnectionInfo", "ConnectionID" to "1") }
    }

    @Test fun transportEventsDoubleEscapeLastChangeAndKeepMetadataReadable() {
        val metadata = didl("<dc:title>歌名 &amp; 引号 &quot; 🎵</dc:title>")
        val snapshot = DlnaSnapshot(DlnaMedia("http://host/song?a=1&b=2", metadata), transportState = "PLAYING",
            positionMs = 24_000, durationMs = 60_000, canSeek = true)
        val event = xml(protocol.event(DlnaService.TRANSPORT, snapshot))
        assertEquals("urn:schemas-upnp-org:event-1-0", event.documentElement.namespaceURI)
        val lastChange = xml(text(event, "LastChange"))
        assertEquals("urn:schemas-upnp-org:metadata-1-0/AVT/", lastChange.documentElement.namespaceURI)
        assertEquals("http://host/song?a=1&b=2", nodes(lastChange, "AVTransportURI").single().getAttribute("val"))
        assertEquals(metadata, nodes(lastChange, "CurrentTrackMetaData").single().getAttribute("val"))
        assertTrue(nodes(lastChange, "RelativeTimePosition").isEmpty())
        assertEquals("0:01:00", nodes(lastChange, "CurrentTrackDuration").single().getAttribute("val"))
    }

    @Test fun advancingPositionIsPolledWithoutChangingLastChange() {
        // AVTransport:1 section 2.3.1 excludes position variables from LastChange events.
        target.state = DlnaSnapshot(DlnaMedia("http://host/song"), transportState = "PLAYING",
            positionMs = 1_000, durationMs = 60_000, canSeek = true)
        val before = protocol.event(DlnaService.TRANSPORT, target.state)
        assertEquals("0:00:01", text(xml(call("GetPositionInfo", "InstanceID" to "0")), "RelTime"))
        target.state = target.state.copy(positionMs = 42_000)
        assertEquals(before, protocol.event(DlnaService.TRANSPORT, target.state))
        assertFalse(before.contains("RelativeTimePosition"))
        assertEquals("0:00:42", text(xml(call("GetPositionInfo", "InstanceID" to "0")), "RelTime"))
    }

    @Test fun renderingAndConnectionEventsExposeTheExpectedStateVariables() {
        val event = xml(protocol.event(DlnaService.RENDERING, DlnaSnapshot(volume = 58, muted = true)))
        val inner = xml(text(event, "LastChange"))
        assertEquals("urn:schemas-upnp-org:metadata-1-0/RCS/", inner.documentElement.namespaceURI)
        assertEquals("58", nodes(inner, "Volume").single().getAttribute("val"))
        assertEquals("Master", nodes(inner, "Volume").single().getAttribute("channel"))
        assertEquals("1", nodes(inner, "Mute").single().getAttribute("val"))
        val connection = xml(protocol.event(DlnaService.CONNECTION, DlnaSnapshot()))
        assertEquals(DlnaProtocol.SINK_PROTOCOLS, text(connection, "SinkProtocolInfo"))
        assertEquals("0", text(connection, "CurrentConnectionIDs"))
    }

    @Test fun faultsAreValidSoapWithEscapedUpnpDetails() {
        val document = xml(DlnaProtocol.fault(DlnaFault(402, "Invalid <args> & value")))
        assertEquals("http://schemas.xmlsoap.org/soap/envelope/", document.documentElement.namespaceURI)
        assertEquals("urn:schemas-upnp-org:control-1-0", nodes(document, "UPnPError").single().namespaceURI)
        assertEquals("402", text(document, "errorCode"))
        assertEquals("Invalid <args> & value", text(document, "errorDescription"))
    }

    @Test fun xmlEscapingPreservesEmojiAndRemovesIllegalXmlCharacters() {
        val text = "你好 🎵 & < > \" ' \u0001\ud800"
        val document = xml("<root attr=\"${DlnaXml.escape(text)}\">${DlnaXml.escape(text)}</root>")
        assertEquals("你好 🎵 & < > \" ' ", document.documentElement.textContent)
        assertEquals(document.documentElement.textContent, document.documentElement.getAttribute("attr"))
    }

    @Test fun timeConversionAcceptsFractionalSecondsAndDoesNotWrapHours() {
        assertEquals(3_723_001L, DlnaProtocol.parseTime("1:02:03.001"))
        assertEquals(100L, DlnaProtocol.parseTime("0:00:00.1"))
        assertEquals(0L, DlnaProtocol.parseTime("00:00:00"))
        assertEquals("27:01:02", DlnaProtocol.time(97_262_999))
        assertEquals("0:00:00", DlnaProtocol.time(-1))
        assertNull(DlnaProtocol.parseTime("NOT_IMPLEMENTED"))
    }

    private fun call(action: String, vararg args: Pair<String, String>) = invoke(DlnaService.TRANSPORT, action, *args)
    private fun render(action: String, vararg args: Pair<String, String>) = invoke(DlnaService.RENDERING, action, *args)
    private fun connect(action: String, vararg args: Pair<String, String>) = invoke(DlnaService.CONNECTION, action, *args)
    private fun invoke(service: DlnaService, action: String, vararg args: Pair<String, String>): String =
        raw(service, action, soap(service, action, args.joinToString("") { "<${it.first}>${escape(it.second)}</${it.first}>" }))
    private fun raw(service: DlnaService, action: String, body: String) =
        protocol.control(service, "\"${service.urn}#$action\"", body, "192.168.1.3")
    private fun soap(service: DlnaService, action: String, fields: String) =
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:$action xmlns:u=\"${service.urn}\">$fields</u:$action></s:Body></s:Envelope>"
    private fun didl(fields: String) = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\">$fields</item></DIDL-Lite>"
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun fault(code: Int, action: () -> Unit) {
        assertEquals(code, assertThrows(DlnaFault::class.java, action).code)
    }
    private fun xml(value: String): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(InputSource(StringReader(value)))
    private fun nodes(document: Document, name: String): List<Element> {
        val list = document.getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }
    private fun text(document: Document, name: String) = nodes(document, name).single().textContent
    private fun childText(element: Element, name: String): String = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }.single { it.localName == name }.textContent
    private fun responseAction(response: String) = nodes(xml(response), "Body").single().firstChild as Element

    private class RecordingTarget : DlnaTarget {
        var state = DlnaSnapshot()
        val commands = mutableListOf<Pair<String, DlnaCommand>>()
        val snapshotPeers = mutableListOf<String>()
        override fun snapshot(peer: String): DlnaSnapshot { snapshotPeers += peer; return state }
        override fun command(peer: String, command: DlnaCommand): DlnaSnapshot { commands += peer to command; return state }
    }
}
