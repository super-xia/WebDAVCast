package com.webdavcast.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 控制条白色矢量图标(24x24 Material 风格, 不依赖扩展图标库)。 */
object CtrlIcons {

    val Audiotrack: ImageVector by lazy {
        ImageVector.Builder("audiotrack", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 9f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(4f)
                lineToRelative(5f, 5f)
                verticalLineTo(4f)
                lineToRelative(-5f, 5f)
                horizontalLineTo(3f)
                close()
                moveTo(16.5f, 12f)
                curveToRelative(0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
                moveTo(14f, 3.23f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5f, 3.54f, 5f, 6.71f)
                reflectiveCurveToRelative(-2.11f, 5.85f, -5f, 6.71f)
                verticalLineToRelative(2.06f)
                curveToRelative(4.01f, -0.91f, 7f, -4.49f, 7f, -8.77f)
                reflectiveCurveToRelative(-2.99f, -7.86f, -7f, -8.77f)
                close()
            }
        }.build()
    }

    val SkipPrevious: ImageVector by lazy {
        ImageVector.Builder("skip_previous", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(12f)
                horizontalLineTo(6f)
                close()
                moveTo(9.5f, 12f)
                lineToRelative(8.5f, 6f)
                verticalLineTo(6f)
                close()
            }
        }.build()
    }

    val FastRewind: ImageVector by lazy {
        ImageVector.Builder("fast_rewind", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(11f, 18f)
                verticalLineTo(6f)
                lineToRelative(-8.5f, 6f)
                lineToRelative(8.5f, 6f)
                close()
                moveTo(11.5f, 12f)
                lineToRelative(8.5f, 6f)
                verticalLineTo(6f)
                lineToRelative(-8.5f, 6f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder("pause", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 19f)
                horizontalLineToRelative(4f)
                verticalLineTo(5f)
                horizontalLineTo(6f)
                verticalLineToRelative(14f)
                close()
                moveTo(14f, 5f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(4f)
                verticalLineTo(5f)
                horizontalLineToRelative(-4f)
                close()
            }
        }.build()
    }

    val PlayArrow: ImageVector by lazy {
        ImageVector.Builder("play_arrow", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(8f, 5f)
                verticalLineToRelative(14f)
                lineToRelative(11f, -7f)
                close()
            }
        }.build()
    }

    val FastForward: ImageVector by lazy {
        ImageVector.Builder("fast_forward", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(4f, 18f)
                lineToRelative(8.5f, -6f)
                lineTo(4f, 6f)
                verticalLineToRelative(12f)
                close()
                moveTo(13f, 6f)
                verticalLineToRelative(12f)
                lineToRelative(8.5f, -6f)
                lineTo(13f, 6f)
                close()
            }
        }.build()
    }

    val SkipNext: ImageVector by lazy {
        ImageVector.Builder("skip_next", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 18f)
                lineToRelative(8.5f, -6f)
                lineTo(6f, 6f)
                verticalLineToRelative(12f)
                close()
                moveTo(16f, 6f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(2f)
                verticalLineTo(6f)
                horizontalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    val Fullscreen: ImageVector by lazy {
        ImageVector.Builder("fullscreen", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7f, 14f)
                horizontalLineTo(5f)
                verticalLineToRelative(5f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(-2f)
                horizontalLineTo(7f)
                verticalLineToRelative(-3f)
                close()
                moveTo(5f, 10f)
                horizontalLineToRelative(2f)
                verticalLineTo(7f)
                horizontalLineToRelative(3f)
                verticalLineTo(5f)
                horizontalLineTo(5f)
                verticalLineToRelative(5f)
                close()
                moveTo(17f, 17f)
                horizontalLineToRelative(-3f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(-5f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(3f)
                close()
                moveTo(14f, 5f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(3f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(2f)
                verticalLineTo(5f)
                horizontalLineToRelative(-5f)
                close()
            }
        }.build()
    }
}