package com.watchlisten.music

import com.watchlisten.music.data.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QualityTest {

    @Test
    fun 默认优先当前账号已授权的最高音质() {
        assertEquals(Quality.HIGH, Quality.fromKey(null))
        assertEquals(Quality.HIGH, Quality.fromKey("不存在的档位"))
    }

    @Test
    fun 按键恢复已保存的档位() {
        Quality.entries.forEach { assertEquals(it, Quality.fromKey(it.key)) }
    }

    @Test
    fun 三个档位对应不同请求值() {
        val values = Quality.entries.map { it.apiValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun 档位都有说明而不是只写标签() {
        Quality.entries.forEach { assertNotEquals("${it} 缺少说明", it.note, it.label) }
    }
}
