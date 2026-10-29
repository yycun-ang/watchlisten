package com.watchlisten.music

import com.watchlisten.music.data.normalizeCoverUrl
import com.watchlisten.music.data.selectCoverUrl
import com.watchlisten.music.data.coverUrlCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverUrlTest {
    @Test
    fun 空Image回退到非空AlbumPic() {
        assertEquals("https://img.kgimg.com/800/album.jpg", selectCoverUrl("", "https://img.kgimg.com/{size}/album.jpg"))
        assertEquals("https://img.kgimg.com/album.jpg", selectCoverUrl("  ", "https://img.kgimg.com/album.jpg"))
    }

    @Test
    fun 有效Image优先于AlbumPic() {
        assertEquals("https://img.kgimg.com/song.jpg", selectCoverUrl("https://img.kgimg.com/song.jpg", "https://img.kgimg.com/album.jpg"))
    }

    @Test
    fun 无效Image仍可回退到AlbumPic() {
        assertEquals("https://img.kgimg.com/album.jpg", selectCoverUrl("not a URL", "https://img.kgimg.com/album.jpg"))
        assertEquals("", selectCoverUrl("", "null"))
    }

    @Test
    fun 官方图片域名和子域的HTTP升级到HTTPS() {
        assertEquals("https://kgimg.com/cover.jpg", normalizeCoverUrl("http://kgimg.com/cover.jpg"))
        assertEquals("https://imge.kgimg.com/800/cover.jpg", normalizeCoverUrl("http://imge.kgimg.com/{size}/cover.jpg"))
        assertEquals("https://IMG.KGIMG.COM/cover.jpg", normalizeCoverUrl("HTTP://IMG.KGIMG.COM/cover.jpg"))
    }

    @Test
    fun 升级HTTP时移除旧默认端口() {
        assertEquals("https://img.kgimg.com/cover.jpg", normalizeCoverUrl("http://img.kgimg.com:80/cover.jpg"))
    }

    @Test
    fun 保留已编码路径查询和片段() {
        assertEquals("https://img.kgimg.com/800/a%20b.jpg?token=a%2Bb#cover",
            normalizeCoverUrl("  http://img.kgimg.com/{size}/a%20b.jpg?token=a%2Bb#cover  "))
    }

    @Test
    fun 其他域名维持原有HTTP策略() {
        assertEquals("http://img.kugou.com/cover.jpg", normalizeCoverUrl("http://img.kugou.com/cover.jpg"))
        assertEquals("http://kgimg.com.example.org/cover.jpg", normalizeCoverUrl("http://kgimg.com.example.org/cover.jpg"))
        assertEquals("http://notkgimg.com/cover.jpg", normalizeCoverUrl("http://notkgimg.com/cover.jpg"))
    }

    @Test
    fun 非HTTP地址和非法地址稳定返回空串() {
        listOf("", "null", "ftp://img.kgimg.com/cover.jpg", "file:///cover.jpg", "data:image/png;base64,AA==",
            "//img.kgimg.com/cover.jpg", "https:///cover.jpg", "https://img.kgimg.com/a b.jpg",
            "https://img.kgimg.com/%ZZ.jpg", "https://img.kgimg.com:99999/cover.jpg",
            "https://user:password@img.kgimg.com/cover.jpg").forEach {
            assertEquals("应拒绝 $it", "", normalizeCoverUrl(it))
        }
    }

    @Test
    fun 官方公开封面的低尺寸优先升级并保留原图回退() {
        listOf("stdmusic", "custom").forEach { type ->
            listOf(160, 240, 320, 400, 480, 640).forEach { size ->
                val original = "https://c1.kgimg.com/$type/$size/20250101/cover.jpg"
                assertEquals(listOf(original.replace("/$size/", "/800/"), original), coverUrlCandidates(original))
            }
        }
    }

    @Test
    fun 显式尺寸规范化不提前丢失旧收藏的回退地址() {
        val original = "https://c1.kgimg.com/stdmusic/400/20250101/cover.jpg"
        assertEquals(original, normalizeCoverUrl(original))
    }

    @Test
    fun 占位符请求800并能回退400() {
        assertEquals(listOf(
            "https://c1.kgimg.com/stdmusic/800/20250101/cover.jpg",
            "https://c1.kgimg.com/stdmusic/400/20250101/cover.jpg"
        ), coverUrlCandidates("https://c1.kgimg.com/stdmusic/{size}/20250101/cover.jpg"))
    }

    @Test
    fun 已有800保持首选并给出服务端失败回退() {
        val original = "https://c1.kgimg.com/custom/800/20250101/cover.jpg"
        assertEquals(listOf(original, original.replace("/800/", "/400/")), coverUrlCandidates(original))
    }

    @Test
    fun 已有更高分辨率不会被降为首选800() {
        val original = "https://c1.kgimg.com/stdmusic/1024/20250101/cover.jpg"
        assertEquals(listOf(original, original.replace("/1024/", "/800/"),
            original.replace("/1024/", "/400/")), coverUrlCandidates(original))
    }

    @Test
    fun 带查询或签名的显式尺寸路径保持原样() {
        listOf("?token=a%2Bb", "?signature=abc&size=400", "?").forEach { query ->
            val original = "https://c1.kgimg.com/stdmusic/400/20250101/cover.jpg$query"
            assertEquals(listOf(original), coverUrlCandidates(original))
        }
    }

    @Test
    fun 原图和未知路径或尺寸不盲目改写() {
        listOf(
            "https://c1.kgimg.com/stdmusic/20250101/cover.jpg",
            "https://c1.kgimg.com/stdmusic/500/20250101/cover.jpg",
            "https://c1.kgimg.com/stdmusic/400/cover.jpg",
            "https://c1.kgimg.com/album/400/20250101/cover.jpg",
            "https://c1.kgimg.com/stdmusic/400/20250101/extra/cover.jpg"
        ).forEach { assertEquals(listOf(it), coverUrlCandidates(it)) }
    }

    @Test
    fun 非官方域名不升级显式尺寸() {
        listOf("example.com", "kgimg.com.example.org", "notkgimg.com", "img.kugou.com").forEach { host ->
            val original = "https://$host/stdmusic/400/20250101/cover.jpg"
            assertEquals(listOf(original), coverUrlCandidates(original))
        }
    }

    @Test
    fun 升级尺寸时保留已编码文件名和片段() {
        val original = "https://c1.kgimg.com/custom/400/20250101/a%20b%2Bc.jpg#cover%20detail"
        assertEquals(listOf(original.replace("/400/", "/800/"), original), coverUrlCandidates(original))
    }

    @Test
    fun 候选地址对非法输入返回空列表() {
        listOf("", "null", "file:///stdmusic/400/20250101/cover.jpg",
            "https://user:pass@c1.kgimg.com/stdmusic/400/20250101/cover.jpg").forEach {
            assertEquals(emptyList<String>(), coverUrlCandidates(it))
        }
    }

    @Test
    fun 旧酷狗图片别名支持HTTPS和高分辨率候选() {
        val original = "http://imge.kugou.com/stdmusic/400/20230913/cover.jpg"
        assertEquals(listOf(
            "https://imge.kugou.com/stdmusic/800/20230913/cover.jpg",
            "https://imge.kugou.com/stdmusic/400/20230913/cover.jpg"
        ), coverUrlCandidates(original))
        assertEquals("https://imge.kugou.com/cover.jpg", normalizeCoverUrl("http://imge.kugou.com:80/cover.jpg"))
    }

    @Test
    fun 官方域名大小写和编码不会被候选构造破坏() {
        assertEquals(listOf(
            "https://C1.KGIMG.COM/stdmusic/800/20250101/cover%2Bname.jpg#front",
            "https://C1.KGIMG.COM/stdmusic/400/20250101/cover%2Bname.jpg#front"
        ), coverUrlCandidates("HTTP://C1.KGIMG.COM:80/stdmusic/400/20250101/cover%2Bname.jpg#front"))
    }
}
