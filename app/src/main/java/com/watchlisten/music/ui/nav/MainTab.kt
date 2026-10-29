package com.watchlisten.music.ui.nav

import androidx.annotation.DrawableRes
import com.watchlisten.music.R

/**
 * 主导航的 tab 定义。
 *
 * 首页、单屏播放器、播放控制。歌词是由播放器上滑打开的详情页。
 * 指示器与内容页读取同一份定义。
 */
enum class MainTab(val id: Int, val route: String, val label: String, @DrawableRes val indicator: Int) {
    HOME(0, "home", "首页", R.drawable.ic_indicator_home),
    PLAYER(1, "player", "播放器", R.drawable.ic_indicator_player),
    CONTROLS(2, "more", "播放控制", R.drawable.ic_settings);

    companion object {
        val count = entries.size

        fun of(index: Int): MainTab = entries.getOrElse(index) { HOME }

        /** route 属于三个 tab 之一时返回对应 tab，否则返回 null。 */
        fun ofRoute(route: String): MainTab? = entries.firstOrNull { it.route == route }
    }
}
