package com.watchlisten.music.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 圆屏界面的语义颜色、几何尺寸与安全区。 */
object WatchTokens {
    val Background = Color.Black
    val Surface = Color(0xFF171817)
    val SurfaceRaised = Color(0xFF252725)
    val Primary = Color(0xFFC8DFE4)
    val PrimaryContainer = Color(0xFF263438)
    val Secondary = Color(0xFFC6D7C5)
    val TextPrimary = Color(0xFFF4F2ED)
    val TextSecondary = Color(0xFFAFB1AD)
    val TextMuted = Color(0xFF91958F)
    val Warning = Color(0xFFE9C58F)
    val Error = Color(0xFFE4AAA5)

    val ContentWidth = 191.dp
    val TopSafeArea = 34.dp
    val BottomSafeArea = 18.dp
    val MinimumTouch = 48.dp
    val CompactActionHeight = 34.dp
    val IconButtonVisual = 26.dp
    val IconSize = 16.dp
    val PrimaryControl = 40.dp
    val ListItemHeight = 48.dp

    val SpaceExtraSmall = 4.dp
    val SpaceSmall = 6.dp
    val SpaceMedium = 10.dp
    val SpaceLarge = 12.dp
    val SpaceExtraLarge = 20.dp

    val ActionRadius = 18.dp
    val ListItemRadius = 18.dp
    val CardRadius = 18.dp
    val IconRadius = 8.dp
}

/** 圆屏上只保留能形成明确层级的排版角色。 */
object WatchTypography {
    val PageTitle = TextStyle(
        color = WatchTokens.TextPrimary,
        fontSize = 17.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold
    )
    val StateTitle = TextStyle(
        color = WatchTokens.TextPrimary,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    )
    val CardTitle = TextStyle(
        color = WatchTokens.TextPrimary,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium
    )
    val Body = TextStyle(
        color = WatchTokens.TextPrimary,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    )
    val Supporting = TextStyle(
        color = WatchTokens.TextSecondary,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal
    )
    val Label = TextStyle(
        color = WatchTokens.TextPrimary,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
    val Metadata = TextStyle(
        color = WatchTokens.TextMuted,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Normal
    )
}

val WatchColorScheme = darkColorScheme(
    primary = WatchTokens.Primary,
    onPrimary = WatchTokens.Background,
    primaryContainer = WatchTokens.PrimaryContainer,
    onPrimaryContainer = WatchTokens.TextPrimary,
    secondary = WatchTokens.Secondary,
    onSecondary = WatchTokens.Background,
    background = WatchTokens.Background,
    onBackground = WatchTokens.TextPrimary,
    surface = WatchTokens.Surface,
    onSurface = WatchTokens.TextPrimary,
    surfaceVariant = WatchTokens.SurfaceRaised,
    onSurfaceVariant = WatchTokens.TextSecondary,
    error = WatchTokens.Error,
    onError = WatchTokens.Background
)

/* 旧页面完成迁移前保留的兼容别名。 */
val Ink = WatchTokens.TextPrimary
val Quiet = WatchTokens.TextSecondary
val Faint = WatchTokens.TextMuted
val KugouBlue = WatchTokens.Primary
val KugouBlueSoft = WatchTokens.PrimaryContainer
val KugouBlueWash = WatchTokens.PrimaryContainer
val OppoGreen = WatchTokens.Secondary
val OppoGreenWash = WatchTokens.SurfaceRaised
val OutputTeal = WatchTokens.Secondary
val WarmRose = WatchTokens.Error
val Good = WatchTokens.Secondary
val Warn = WatchTokens.Warning
val Bad = WatchTokens.Error
val Panel = WatchTokens.Surface
val PanelHi = WatchTokens.SurfaceRaised
val MusicColors = WatchColorScheme

object Gap {
    val xs = WatchTokens.SpaceExtraSmall
    val sm = WatchTokens.SpaceSmall
    val md = WatchTokens.SpaceLarge
    val lg = WatchTokens.SpaceExtraLarge
}
