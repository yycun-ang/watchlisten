package com.watchlisten.music.dlna

import org.w3c.dom.Element
import java.net.URI
import java.util.Locale

/** 原生 UPnP 音乐接收协议；所有实际播放与控制手机校验交给唯一的 target。 */
class DlnaProtocol(val identity: DlnaIdentity, val target: DlnaTarget) {
    fun description(baseUrl: String): String {
        val base = try { URI(baseUrl) } catch (_: Exception) { throw IllegalArgumentException("Invalid base URL") }
        require(base.scheme in setOf("http", "https") && base.host != null && base.rawUserInfo == null &&
            base.rawQuery == null && base.rawFragment == null && base.path in setOf("", "/"))
        val services = DlnaService.entries.joinToString("") { service ->
            "<service><serviceType>${service.urn}</serviceType><serviceId>urn:upnp-org:serviceId:${service.id}</serviceId>" +
                "<SCPDURL>/${service.path}.xml</SCPDURL><controlURL>/${service.path}/control</controlURL>" +
                "<eventSubURL>/${service.path}/event</eventSubURL></service>"
        }
        val uuid = if (identity.uuid.startsWith("uuid:")) identity.uuid else "uuid:${identity.uuid}"
        return XML + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<URLBase>${x(baseUrl.trimEnd('/') + "/")}</URLBase><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
            "<friendlyName>${x(identity.name)}</friendlyName><manufacturer>存存</manufacturer>" +
            "<modelDescription>腕上听音乐投放接收器</modelDescription><modelName>${x(identity.model)}</modelName>" +
            "<modelNumber>${x(identity.version)}</modelNumber><UDN>${x(uuid)}</UDN><serviceList>$services</serviceList></device></root>"
    }

    fun serviceDescription(service: DlnaService): String {
        val actions = actions(service).joinToString("") { action ->
            val arguments = action.arguments.joinToString("") {
                "<argument><name>${it.name}</name><direction>${it.direction}</direction><relatedStateVariable>${it.state}</relatedStateVariable></argument>"
            }
            "<action><name>${action.name}</name>" + if (arguments.isEmpty()) "</action>" else "<argumentList>$arguments</argumentList></action>"
        }
        val states = states(service).joinToString("") { state ->
            "<stateVariable sendEvents=\"${if (state.evented) "yes" else "no"}\"><name>${state.name}</name><dataType>${state.type}</dataType>" +
                (state.default?.let { "<defaultValue>${x(it)}</defaultValue>" } ?: "") +
                (if (state.values.isEmpty()) "" else "<allowedValueList>${state.values.joinToString("") { "<allowedValue>${x(it)}</allowedValue>" }}</allowedValueList>") +
                (if (state.volume) "<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>" else "") +
                "</stateVariable>"
        }
        return XML + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>$actions</actionList><serviceStateTable>$states</serviceStateTable></scpd>"
    }

    fun control(service: DlnaService, soapAction: String, body: String, peer: String): String {
        val request = request(service, soapAction, body)
        val definitions = actions(service)
        val action = definitions.firstOrNull { it.name == request.name } ?: throw DlnaFault(401, "Invalid Action")
        val inputs = action.arguments.filter { it.direction == "in" }.map { it.name }.toSet()
        if (request.args.keys != inputs) throw DlnaFault(402, "Invalid Args")
        if (service != DlnaService.CONNECTION && request.required("InstanceID").toLongOrNull() != 0L)
            throw DlnaFault(718, "Invalid InstanceID")
        val values = when (service) {
            DlnaService.TRANSPORT -> transport(request, peer)
            DlnaService.RENDERING -> rendering(request, peer)
            DlnaService.CONNECTION -> connection(request)
        }
        val payload = values.entries.joinToString("") { "<${it.key}>${x(it.value)}</${it.key}>" }
        return envelope("<u:${request.name}Response xmlns:u=\"${service.urn}\">$payload</u:${request.name}Response>")
    }

    fun event(service: DlnaService, snapshot: DlnaSnapshot): String {
        val properties = when (service) {
            DlnaService.TRANSPORT -> {
                val variables = linkedMapOf(
                    "TransportState" to snapshot.transportState,
                    "TransportStatus" to if (snapshot.failed) "ERROR_OCCURRED" else "OK",
                    "AVTransportURI" to snapshot.media?.uri.orEmpty(),
                    "AVTransportURIMetaData" to snapshot.media?.metadata.orEmpty(),
                    "NextAVTransportURI" to snapshot.next?.uri.orEmpty(),
                    "NextAVTransportURIMetaData" to snapshot.next?.metadata.orEmpty(),
                    "CurrentTrack" to if (snapshot.media == null) "0" else "1",
                    "CurrentTrackURI" to snapshot.media?.uri.orEmpty(),
                    "CurrentTrackMetaData" to snapshot.media?.metadata.orEmpty(),
                    "CurrentTrackDuration" to time(snapshot.durationMs),
                    "CurrentMediaDuration" to time(snapshot.durationMs),
                    "CurrentPlayMode" to snapshot.playMode,
                    "CurrentTransportActions" to currentActions(snapshot)
                )
                val values = variables.entries.joinToString("") { "<${it.key} val=\"${x(it.value)}\"/>" }
                mapOf("LastChange" to "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">$values</InstanceID></Event>")
            }
            DlnaService.RENDERING -> mapOf("LastChange" to
                "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
                "<Volume channel=\"Master\" val=\"${snapshot.volume.coerceIn(0, 100)}\"/>" +
                "<Mute channel=\"Master\" val=\"${if (snapshot.muted) 1 else 0}\"/></InstanceID></Event>")
            DlnaService.CONNECTION -> linkedMapOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to SINK_PROTOCOLS, "CurrentConnectionIDs" to "0")
        }
        return XML + "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            properties.entries.joinToString("") { "<e:property><${it.key}>${x(it.value)}</${it.key}></e:property>" } + "</e:propertyset>"
    }

    private fun transport(request: Request, peer: String): Map<String, String> {
        val command = when (request.name) {
            "SetAVTransportURI" -> DlnaCommand.SetUri(media(request.required("CurrentURI"), request.required("CurrentURIMetaData")))
            "SetNextAVTransportURI" -> DlnaCommand.SetUri(media(request.required("NextURI"), request.required("NextURIMetaData")), next = true)
            "Play" -> {
                if (request.required("Speed") != "1") throw DlnaFault(717, "Play speed not supported")
                DlnaCommand.Play
            }
            "Pause" -> DlnaCommand.Pause
            "Stop" -> DlnaCommand.Stop
            "Next" -> DlnaCommand.Next
            "Previous" -> DlnaCommand.Previous
            "Seek" -> {
                if (request.required("Unit") !in setOf("REL_TIME", "ABS_TIME")) throw DlnaFault(710, "Seek mode not supported")
                DlnaCommand.Seek(parseTime(request.required("Target")) ?: throw DlnaFault(711, "Illegal seek target"))
            }
            "SetPlayMode" -> DlnaCommand.PlayMode(request.required("NewPlayMode").also {
                if (it !in PLAY_MODES) throw DlnaFault(712, "Play mode not supported")
            })
            else -> null
        }
        if (command != null) { target.command(peer, command); return emptyMap() }
        if (request.name == "GetDeviceCapabilities") return linkedMapOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
        val snapshot = target.snapshot(peer)
        return when (request.name) {
            "GetTransportInfo" -> linkedMapOf("CurrentTransportState" to snapshot.transportState,
                "CurrentTransportStatus" to if (snapshot.failed) "ERROR_OCCURRED" else "OK", "CurrentSpeed" to "1")
            "GetPositionInfo" -> linkedMapOf("Track" to if (snapshot.media == null) "0" else "1",
                "TrackDuration" to time(snapshot.durationMs), "TrackMetaData" to snapshot.media?.metadata.orEmpty(),
                "TrackURI" to snapshot.media?.uri.orEmpty(), "RelTime" to time(snapshot.positionMs),
                "AbsTime" to time(snapshot.positionMs), "RelCount" to "2147483647", "AbsCount" to "2147483647")
            "GetMediaInfo" -> linkedMapOf("NrTracks" to if (snapshot.media == null) "0" else "1",
                "MediaDuration" to time(snapshot.durationMs), "CurrentURI" to snapshot.media?.uri.orEmpty(),
                "CurrentURIMetaData" to snapshot.media?.metadata.orEmpty(), "NextURI" to snapshot.next?.uri.orEmpty(),
                "NextURIMetaData" to snapshot.next?.metadata.orEmpty(), "PlayMedium" to if (snapshot.media == null) "NONE" else "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED")
            "GetTransportSettings" -> linkedMapOf("PlayMode" to snapshot.playMode, "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> mapOf("Actions" to currentActions(snapshot))
            else -> throw DlnaFault(401, "Invalid Action")
        }
    }

    private fun rendering(request: Request, peer: String): Map<String, String> {
        if (request.required("Channel") != "Master") throw DlnaFault(402, "Invalid Args")
        when (request.name) {
            "SetVolume" -> target.command(peer, DlnaCommand.Volume(request.required("DesiredVolume").toIntOrNull()
                ?.takeIf { it in 0..100 } ?: throw DlnaFault(402, "Invalid Args")))
            "SetMute" -> target.command(peer, DlnaCommand.Mute(when (request.required("DesiredMute").lowercase(Locale.ROOT)) {
                "0", "false" -> false
                "1", "true" -> true
                else -> throw DlnaFault(402, "Invalid Args")
            }))
            "GetVolume" -> return mapOf("CurrentVolume" to target.snapshot(peer).volume.coerceIn(0, 100).toString())
            "GetMute" -> return mapOf("CurrentMute" to if (target.snapshot(peer).muted) "1" else "0")
            else -> throw DlnaFault(401, "Invalid Action")
        }
        return emptyMap()
    }

    private fun connection(request: Request): Map<String, String> = when (request.name) {
        "GetProtocolInfo" -> linkedMapOf("Source" to "", "Sink" to SINK_PROTOCOLS)
        "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> {
            if (request.required("ConnectionID").toLongOrNull() != 0L) throw DlnaFault(706, "Invalid connection reference")
            linkedMapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to SINK_PROTOCOLS.split(',').first(),
                "PeerConnectionManager" to "", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
        }
        else -> throw DlnaFault(401, "Invalid Action")
    }

    private fun request(service: DlnaService, header: String, body: String): Request {
        val action = header.trim().removeSurrounding("\"")
        if (!action.startsWith(service.urn + "#")) throw DlnaFault(401, "Invalid Action")
        val name = action.substringAfter('#')
        if (!name.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) throw DlnaFault(401, "Invalid Action")
        val root = DlnaXml.parse(body).documentElement
        if (root.localName != "Envelope" || root.namespaceURI != SOAP) throw DlnaFault(402, "Invalid Args")
        val bodies = DlnaXml.children(root).filter { it.localName == "Body" && it.namespaceURI == SOAP }
        if (bodies.size != 1) throw DlnaFault(402, "Invalid Args")
        val calls = DlnaXml.children(bodies.single())
        if (calls.size != 1 || calls.single().localName != name || calls.single().namespaceURI != service.urn)
            throw DlnaFault(401, "Invalid Action")
        val args = linkedMapOf<String, String>()
        for (field in DlnaXml.children(calls.single())) {
            if (DlnaXml.children(field).isNotEmpty() || args.put(field.localName ?: field.tagName, field.textContent) != null)
                throw DlnaFault(402, "Invalid Args")
        }
        return Request(name, args)
    }

    private fun media(uri: String, metadata: String): DlnaMedia {
        if (uri.length > 8192 || uri.any { it.code < 32 || it.code == 127 } ||
            metadata.toByteArray(Charsets.UTF_8).size > DlnaXml.MAX_METADATA_BYTES) throw DlnaFault(402, "Invalid Args")
        if (metadata.isBlank()) return DlnaMedia(uri)
        val document = DlnaXml.parse(metadata, DlnaXml.MAX_METADATA_BYTES)
        if (document.documentElement.localName != "DIDL-Lite" || document.documentElement.namespaceURI != DIDL)
            throw DlnaFault(402, "Invalid Args")
        val item = DlnaXml.children(document.documentElement).firstOrNull { it.localName == "item" }
            ?: throw DlnaFault(402, "Invalid Args")
        val fields = DlnaXml.children(item)
        fun text(localName: String, max: Int = 512) = fields.firstOrNull { it.localName == localName }?.textContent.orEmpty().trim().take(max)
        val resources = fields.filter { it.localName == "res" }
        val resource = resources.firstOrNull { it.textContent.trim() == uri } ?: resources.firstOrNull()
        val mime = resource?.getAttribute("protocolInfo")?.split(':', limit = 4)?.getOrNull(2).orEmpty().lowercase(Locale.ROOT)
        if (mime.isNotBlank() && mime != "*" && mime != "application/octet-stream" && !mime.startsWith("audio/"))
            throw DlnaFault(714, "Illegal MIME-type")
        return DlnaMedia(uri, metadata, text("title").ifBlank { "手机投放" }, text("artist").ifBlank { text("creator") },
            text("album"), text("albumArtURI", 8192), resource?.getAttribute("duration")?.let(::parseTime) ?: 0,
            mime.takeUnless { it == "*" || it == "application/octet-stream" }.orEmpty())
    }

    private data class Request(val name: String, val args: Map<String, String>) {
        fun required(name: String): String = args[name] ?: throw DlnaFault(402, "Invalid Args")
    }

    private data class Argument(val name: String, val direction: String, val state: String)
    private data class Action(val name: String, val arguments: List<Argument>)
    private data class State(val name: String, val type: String = "string", val default: String? = null,
                             val values: List<String> = emptyList(), val evented: Boolean = false, val volume: Boolean = false)

    private fun actions(service: DlnaService): List<Action> {
        fun action(name: String, vararg args: String) = Action(name, args.map {
            val pieces = it.split(':'); Argument(pieces[0], pieces[1], pieces[2])
        })
        val instance = "InstanceID:in:A_ARG_TYPE_InstanceID"
        val channel = "Channel:in:A_ARG_TYPE_Channel"
        return when (service) {
            DlnaService.TRANSPORT -> listOf(
                action("SetAVTransportURI", instance, "CurrentURI:in:AVTransportURI", "CurrentURIMetaData:in:AVTransportURIMetaData"),
                action("SetNextAVTransportURI", instance, "NextURI:in:NextAVTransportURI", "NextURIMetaData:in:NextAVTransportURIMetaData"),
                action("Play", instance, "Speed:in:TransportPlaySpeed"), action("Pause", instance), action("Stop", instance),
                action("Next", instance), action("Previous", instance),
                action("Seek", instance, "Unit:in:A_ARG_TYPE_SeekMode", "Target:in:A_ARG_TYPE_SeekTarget"),
                action("SetPlayMode", instance, "NewPlayMode:in:CurrentPlayMode"),
                action("GetTransportInfo", instance, "CurrentTransportState:out:TransportState", "CurrentTransportStatus:out:TransportStatus", "CurrentSpeed:out:TransportPlaySpeed"),
                action("GetPositionInfo", instance, "Track:out:CurrentTrack", "TrackDuration:out:CurrentTrackDuration", "TrackMetaData:out:CurrentTrackMetaData",
                    "TrackURI:out:CurrentTrackURI", "RelTime:out:RelativeTimePosition", "AbsTime:out:AbsoluteTimePosition", "RelCount:out:RelativeCounterPosition", "AbsCount:out:AbsoluteCounterPosition"),
                action("GetMediaInfo", instance, "NrTracks:out:NumberOfTracks", "MediaDuration:out:CurrentMediaDuration", "CurrentURI:out:AVTransportURI",
                    "CurrentURIMetaData:out:AVTransportURIMetaData", "NextURI:out:NextAVTransportURI", "NextURIMetaData:out:NextAVTransportURIMetaData",
                    "PlayMedium:out:PlaybackStorageMedium", "RecordMedium:out:RecordStorageMedium", "WriteStatus:out:RecordMediumWriteStatus"),
                action("GetTransportSettings", instance, "PlayMode:out:CurrentPlayMode", "RecQualityMode:out:CurrentRecordQualityMode"),
                action("GetDeviceCapabilities", instance, "PlayMedia:out:PossiblePlaybackStorageMedia", "RecMedia:out:PossibleRecordStorageMedia", "RecQualityModes:out:PossibleRecordQualityModes"),
                action("GetCurrentTransportActions", instance, "Actions:out:CurrentTransportActions")
            )
            DlnaService.RENDERING -> listOf(
                action("SetVolume", instance, channel, "DesiredVolume:in:Volume"), action("GetVolume", instance, channel, "CurrentVolume:out:Volume"),
                action("SetMute", instance, channel, "DesiredMute:in:Mute"), action("GetMute", instance, channel, "CurrentMute:out:Mute")
            )
            DlnaService.CONNECTION -> listOf(
                action("GetProtocolInfo", "Source:out:SourceProtocolInfo", "Sink:out:SinkProtocolInfo"),
                action("GetCurrentConnectionIDs", "ConnectionIDs:out:CurrentConnectionIDs"),
                action("GetCurrentConnectionInfo", "ConnectionID:in:A_ARG_TYPE_ConnectionID", "RcsID:out:A_ARG_TYPE_RcsID", "AVTransportID:out:A_ARG_TYPE_AVTransportID",
                    "ProtocolInfo:out:A_ARG_TYPE_ProtocolInfo", "PeerConnectionManager:out:A_ARG_TYPE_ConnectionManager", "PeerConnectionID:out:A_ARG_TYPE_ConnectionID",
                    "Direction:out:A_ARG_TYPE_Direction", "Status:out:A_ARG_TYPE_ConnectionStatus")
            )
        }
    }

    private fun states(service: DlnaService): List<State> = when (service) {
        DlnaService.TRANSPORT -> listOf(
            State("TransportState", values = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT")),
            State("TransportStatus", values = listOf("OK", "ERROR_OCCURRED")),
            State("PlaybackStorageMedium", values = listOf("NETWORK", "NONE")), State("RecordStorageMedium", default = "NOT_IMPLEMENTED"),
            State("RecordMediumWriteStatus", default = "NOT_IMPLEMENTED"), State("PossiblePlaybackStorageMedia", default = "NETWORK"),
            State("PossibleRecordStorageMedia", default = "NOT_IMPLEMENTED"), State("CurrentPlayMode", default = "NORMAL", values = PLAY_MODES),
            State("TransportPlaySpeed", default = "1", values = listOf("1")), State("CurrentRecordQualityMode", default = "NOT_IMPLEMENTED"),
            State("PossibleRecordQualityModes", default = "NOT_IMPLEMENTED"), State("NumberOfTracks", "ui4", "0"), State("CurrentTrack", "ui4", "0"),
            State("CurrentTrackDuration", default = "0:00:00"), State("CurrentMediaDuration", default = "0:00:00"),
            State("CurrentTrackMetaData"), State("CurrentTrackURI", "uri"), State("AVTransportURI", "uri"), State("AVTransportURIMetaData"),
            State("NextAVTransportURI", "uri"), State("NextAVTransportURIMetaData"), State("RelativeTimePosition", default = "0:00:00"),
            State("AbsoluteTimePosition", default = "0:00:00"), State("RelativeCounterPosition", "i4", "2147483647"),
            State("AbsoluteCounterPosition", "i4", "2147483647"), State("CurrentTransportActions"),
            State("A_ARG_TYPE_SeekMode", values = listOf("REL_TIME", "ABS_TIME")), State("A_ARG_TYPE_SeekTarget"),
            State("A_ARG_TYPE_InstanceID", "ui4", "0"), State("LastChange", evented = true)
        )
        DlnaService.RENDERING -> listOf(State("Volume", "ui2", "0", volume = true), State("Mute", "boolean", "0"),
            State("A_ARG_TYPE_Channel", default = "Master", values = listOf("Master")), State("A_ARG_TYPE_InstanceID", "ui4", "0"), State("LastChange", evented = true))
        DlnaService.CONNECTION -> listOf(State("SourceProtocolInfo", default = "", evented = true),
            State("SinkProtocolInfo", default = SINK_PROTOCOLS, evented = true), State("CurrentConnectionIDs", default = "0", evented = true),
            State("A_ARG_TYPE_ConnectionID", "i4", "0"), State("A_ARG_TYPE_RcsID", "i4", "0"), State("A_ARG_TYPE_AVTransportID", "i4", "0"),
            State("A_ARG_TYPE_ProtocolInfo"), State("A_ARG_TYPE_ConnectionManager"), State("A_ARG_TYPE_Direction", values = listOf("Input")),
            State("A_ARG_TYPE_ConnectionStatus", values = listOf("OK", "Unknown", "UnreliableChannel", "ContentFormatMismatch", "InsufficientBandwidth")))
    }

    companion object {
        private const val SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val DIDL = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
        private const val XML = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        private val PLAY_MODES = listOf("NORMAL", "REPEAT_ONE", "REPEAT_ALL", "SHUFFLE", "SHUFFLE_NOREPEAT", "RANDOM")
        const val SINK_PROTOCOLS = "http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*,http-get:*:audio/aac:*,http-get:*:audio/mp4:*,http-get:*:audio/wav:*,http-get:*:audio/x-wav:*"
        private fun x(value: String) = DlnaXml.escape(value)
        private fun envelope(value: String) = XML + "<s:Envelope xmlns:s=\"$SOAP\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$value</s:Body></s:Envelope>"

        fun fault(fault: DlnaFault): String = envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
            "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>${fault.code}</errorCode>" +
            "<errorDescription>${x(fault.description)}</errorDescription></UPnPError></detail></s:Fault>")

        fun time(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            return "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, (seconds / 60) % 60, seconds % 60)
        }

        fun parseTime(value: String): Long? {
            val match = Regex("^(\\d{1,6}):([0-5]\\d):([0-5]\\d)(?:\\.(\\d{1,3}))?$").matchEntire(value) ?: return null
            val (hours, minutes, seconds, fraction) = match.destructured
            return (hours.toLong() * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000 + fraction.padEnd(3, '0').toLongOrNull().orZero()
        }

        private fun Long?.orZero() = this ?: 0L

        private fun currentActions(snapshot: DlnaSnapshot): String = if (snapshot.media == null) "" else buildList {
            if (snapshot.transportState != "PLAYING") add("Play")
            if (snapshot.transportState in setOf("PLAYING", "TRANSITIONING")) add("Pause")
            add("Stop")
            if (snapshot.canSeek) add("Seek")
            if (snapshot.canNext) add("Next")
            if (snapshot.canPrevious) add("Previous")
        }.joinToString(",")
    }
}
