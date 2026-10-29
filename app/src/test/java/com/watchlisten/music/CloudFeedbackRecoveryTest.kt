package com.watchlisten.music

import com.watchlisten.music.ui.CloudFeedbackAction
import com.watchlisten.music.ui.cloudFeedbackPresentation
import org.junit.Assert.*
import org.junit.Test

class CloudFeedbackRecoveryTest {
    @Test fun 撤销失败时提供重试撤销而不是刷新() {
        val presentation = cloudFeedbackPresentation(null, "连接暂时中断", true, 60_000, 20_000, "刷新")!!
        assertEquals("连接暂时中断", presentation.text)
        assertEquals(CloudFeedbackAction.UNDO, presentation.action)
        assertEquals("重试撤销", presentation.actionLabel)
    }

    @Test fun 撤销失败后刷新成功清空错误仍保留有效撤销入口() {
        val failed = cloudFeedbackPresentation(null, "连接暂时中断", true, 60_000, 20_000, "刷新")!!
        val refreshed = cloudFeedbackPresentation(null, null, true, 60_000, 30_000, "刷新")!!
        assertEquals(CloudFeedbackAction.UNDO, failed.action)
        assertEquals(CloudFeedbackAction.UNDO, refreshed.action)
        assertTrue(refreshed.text.isNotBlank())
        assertEquals("撤销", refreshed.actionLabel)
    }

    @Test fun 撤销到期时不能继续提供写入入口但保留错误重试() {
        val presentation = cloudFeedbackPresentation(null, "连接暂时中断", true, 60_000, 60_000, "刷新")!!
        assertEquals(CloudFeedbackAction.RETRY, presentation.action)
        assertEquals("刷新", presentation.actionLabel)
        assertNull(cloudFeedbackPresentation(null, null, true, 60_000, 60_001, "刷新"))
    }

    @Test fun 成功恢复后显示成功文案且不再提供撤销() {
        val presentation = cloudFeedbackPresentation("已恢复酷狗喜欢", null, false, 0, 30_000, "刷新")!!
        assertEquals("已恢复酷狗喜欢", presentation.text)
        assertNull(presentation.action)
        assertNull(presentation.actionLabel)
    }

    @Test fun 普通错误保持原有重试入口且没有凭空生成撤销() {
        val presentation = cloudFeedbackPresentation(null, "需要重新登录", false, 60_000, 20_000, "确认")!!
        assertEquals(CloudFeedbackAction.RETRY, presentation.action)
        assertEquals("确认", presentation.actionLabel)
        assertNull(cloudFeedbackPresentation(null, null, false, 0, 20_000, "确认"))
    }
}
