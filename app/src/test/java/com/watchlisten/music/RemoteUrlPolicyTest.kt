package com.watchlisten.music

import com.watchlisten.music.dlna.RemoteUrlPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

class RemoteUrlPolicyTest {
    private val peer = "192.168.31.20"

    @Test fun controllerCanServeHttpAndHttpsOnExplicitPorts() {
        listOf("http://$peer/audio.mp3", "https://$peer:8443/audio.flac?signature=secret").forEach {
            assertEquals(peer, RemoteUrlPolicy.validate(it, peer).host)
        }
        listOf("10.1.2.3", "172.16.2.3", "172.31.255.254", "192.168.1.1").forEach {
            assertEquals(it, RemoteUrlPolicy.validate("http://$it/song", it).host)
        }
    }

    @Test fun controllerUrlCannotPointAtAnotherLanDevice() {
        listOf("192.168.31.1", "10.0.0.1", "127.0.0.1", "169.254.1.2", "8.8.8.8").forEach {
            reject("http://$it/music.mp3?signature=secret")
        }
    }

    @Test fun officialDomainsHaveAnExactLabelBoundary() {
        listOf("kugou.com", "media.kugou.com", "KGMusic.com", "fs.kugou.net", "img.kgimg.com").forEach {
            assertNotNull(RemoteUrlPolicy.validate("https://$it/song?signature=secret", peer))
        }
        listOf("evilkugou.com", "kugou.com.evil.test", "kugou.com.", "-a.kugou.com", "a..kgimg.com").forEach {
            reject("https://$it/song?signature=secret")
        }
    }

    @Test fun onlyStrictPrivateIpv4PeersAreAccepted() {
        listOf("127.0.0.1", "169.254.1.1", "8.8.8.8", "100.64.0.1", "172.32.0.1", "192.168.01.1", "10.1", "::1", "10.0.0.256").forEach {
            assertThrows(IOException::class.java) { RemoteUrlPolicy.validate("https://media.kugou.com/song", it) }
        }
    }

    @Test fun unsafeUriComponentsAndPortsAreRejected() {
        listOf(
            "file://$peer/song", "ftp://$peer/song", "http://user:password@$peer/song", "http://$peer/song#fragment",
            "http://$peer:0/song", "http://$peer:65536/song", "http://$peer:/song", "http://$peer:-1/song",
            "http://$peer/song\r\nAuthorization: secret", "http://$peer/space here", "http://$peer\\other/song"
        ).forEach(::reject)
    }

    @Test fun bothRawAndAsciiEncodedUrlLengthAreBounded() {
        val prefix = "http://$peer/"
        assertNotNull(RemoteUrlPolicy.validate(prefix + "a".repeat(8192 - prefix.length), peer))
        reject(prefix + "a".repeat(8193 - prefix.length))
        reject(prefix + "歌".repeat(1000))
    }

    @Test fun controllerDnsAddressIsPinnedToTheRequestingPhone() {
        val uri = RemoteUrlPolicy.validate("http://$peer/music", peer)
        RemoteUrlPolicy.validateAddress(uri, InetAddress.getByName(peer), peer)
        listOf("192.168.31.21", "8.8.8.8", "127.0.0.1").forEach {
            assertThrows(IOException::class.java) { RemoteUrlPolicy.validateAddress(uri, InetAddress.getByName(it), peer) }
        }
    }

    @Test fun officialDnsMustResolveToPublicUnicastAddresses() {
        val uri = RemoteUrlPolicy.validate("https://media.kugou.com/music", peer)
        listOf("8.8.8.8", "223.5.5.5", "2001:4860:4860::8888", "240e::1").forEach {
            RemoteUrlPolicy.validateAddress(uri, InetAddress.getByName(it), peer)
        }
        listOf(
            "0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.1.1", "172.16.0.1", "192.168.1.1",
            "100.64.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "2002:0a00:0001::1", "64:ff9b::a00:1"
        ).forEach {
            assertThrows("Address must be rejected: $it", IOException::class.java) {
                RemoteUrlPolicy.validateAddress(uri, InetAddress.getByName(it), peer)
            }
        }
    }

    @Test fun scopedIpv6CannotUseAnOfficialHostnameToReachALocalInterface() {
        val uri = RemoteUrlPolicy.validate("https://media.kugou.com/music", peer)
        val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("240e::1").address, 2)
        assertThrows(IOException::class.java) { RemoteUrlPolicy.validateAddress(uri, scoped, peer) }
    }

    @Test fun rejectedAddressesNeverAppearInTheErrorMessage() {
        val url = "https://evil.test/music?signature=super-secret-token"
        val error = assertThrows(IOException::class.java) { RemoteUrlPolicy.validate(url, peer) }
        assertFalse(error.toString().contains(url))
        assertFalse(error.toString().contains("super-secret-token"))
        assertNull(error.cause)
    }

    private fun reject(url: String) {
        assertThrows("URL must be rejected", IOException::class.java) { RemoteUrlPolicy.validate(url, peer) }
    }
}
