package com.watchlisten.music

import com.watchlisten.music.model.Track
import com.watchlisten.music.model.TrackRights
import com.watchlisten.music.model.ListeningAccess
import com.watchlisten.music.ui.trackSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackSubtitleTest {

    private fun track(payType: Int, durationMs: Long, artist: String = "林屿") =
        Track("a".repeat(32), "夜航", artist, durationMs = durationMs, payType = payType)

    @Test
    fun 免费歌曲显示时长与免费标记() {
        assertEquals("林屿 · 3:44 · 免费", trackSubtitle(track(0, 224000)))
    }

    @Test
    fun 未核实的付费码标注待确认而不是承诺不可播() {
        val text = trackSubtitle(track(2, 198000))
        assertEquals("林屿 · 3:18 · 权益待确认", text)
        assertFalse("不应断言一定无法播放", text.contains("不可"))
    }

    @Test
    fun 接口未提供权限码时不显示多余标记() {
        assertEquals("林屿 · 3:44", trackSubtitle(track(-1, 224000)))
    }

    @Test
    fun 缺少时长时不显示空占位() {
        assertEquals("林屿 · 免费", trackSubtitle(track(0, 0)))
    }

    @Test
    fun 缺少歌手时不留多余分隔符() {
        assertEquals("3:44 · 免费", trackSubtitle(track(0, 224000, "")))
    }

    @Test
    fun 时长按分钟秒格式化() {
        assertEquals("林屿 · 1:14 · 免费", trackSubtitle(track(0, 74000)))
        assertEquals("林屿 · 0:05", trackSubtitle(track(-1, 5000)))
    }

    @Test
    fun 会员歌曲不能仅凭曲库标记承诺完整播放() {
        val song = track(3, 224000).copy(rights = TrackRights.VIP)
        assertEquals("林屿 · 3:44 · 以授权为准", trackSubtitle(song))
        assertEquals("林屿 · 3:44 · 仅可试听", trackSubtitle(song, ListeningAccess.PREVIEW))
        assertEquals("林屿 · 3:44 · 可完整播放", trackSubtitle(song, ListeningAccess.FULL))
    }

    @Test
    fun 免费标记也不能覆盖本次服务端拒绝() {
        assertEquals("林屿 · 3:44 · 无播放权限",
            trackSubtitle(track(0, 224000), ListeningAccess.DENIED))
    }
}
