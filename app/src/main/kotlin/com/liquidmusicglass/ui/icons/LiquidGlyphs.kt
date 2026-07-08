package com.liquidmusicglass.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Selected custom LiquidMusicGlass glyphs generated from Download/LiquidGlyphs_selected.zip.
 * Keep these monochrome/currentColor-compatible: call sites control semantic tint.
 */
object LiquidGlyphs {
    val Add: ImageVector get() = RawAddV4
    val Album: ImageVector get() = RawAlbumV4
    val AirSheetDevices: ImageVector get() = RawCastDevicesV4
    val Automix: ImageVector get() = RawAutomixBlocks
    val Back: ImageVector get() = RawBackV3
    val CastDevices: ImageVector get() = RawCastDevicesV4
    val Chat: ImageVector get() = RawChatV4
    val Check: ImageVector get() = RawCheckV3
    val CheckCircle: ImageVector get() = RawCheckV3
    val ChevronRight: ImageVector get() = RawChevronRightV3
    val Close: ImageVector get() = RawCloseV3
    val Download: ImageVector get() = RawDownloadTrack
    val DownloadTrack: ImageVector get() = RawDownloadTrack
    val Edit: ImageVector get() = RawEditV3
    val Equalizer: ImageVector get() = RawEqualizerPills
    val Error: ImageVector get() = RawErrorV3
    val ErrorOutline: ImageVector get() = RawErrorV3
    val Favorite: ImageVector get() = RawHeartV2
    val FavoriteBorder: ImageVector get() = RawHeartV2
    val GraphicEq: ImageVector get() = RawEqualizerPills
    val Headphones: ImageVector get() = RawHeadphonesV2
    val Heart: ImageVector get() = RawHeartV2
    val History: ImageVector get() = RawHistoryV2
    val Home: ImageVector get() = RawHomeOrbit
    val Import: ImageVector get() = RawImportArrowCards
    val LocalAudio: ImageVector get() = RawLocalDisc
    val More: ImageVector get() = RawMoreV4
    val MoreHoriz: ImageVector get() = RawMoreV4
    val MusicAppOpen: ImageVector get() = RawMusicAppOpenV3
    val MusicNote: ImageVector get() = RawMusicNoteV1
    val Next: ImageVector get() = RawNextV1
    val Pause: ImageVector get() = RawPauseV1
    val Person: ImageVector get() = RawPersonV3
    val PersonAddAlt1: ImageVector get() = RawPersonV3
    val Play: ImageVector get() = RawPlayV1
    val PlayArrow: ImageVector get() = RawPlayV1
    val Playlist: ImageVector get() = RawPlaylistCards
    val PlaylistAdd: ImageVector get() = RawPlaylistAddV3
    val Previous: ImageVector get() = RawPreviousV1
    val Quality: ImageVector get() = RawQualityV3
    val Queue: ImageVector get() = RawQueueV3
    val QueueMusic: ImageVector get() = RawQueueV3
    val Radio: ImageVector get() = RawRadioDisc
    val Refresh: ImageVector get() = RawRefreshV2
    val Repeat: ImageVector get() = RawRepeatV3
    val RepeatOne: ImageVector get() = RawRepeatV3
    val Search: ImageVector get() = RawSearchV3
    val Settings: ImageVector get() = RawSettingsPanel
    val Share: ImageVector get() = RawShareV3
    val Star: ImageVector get() = RawStarV2
    val ThumbDown: ImageVector get() = RawThumbDownV3
    val ThumbUp: ImageVector get() = RawThumbUpV3
    val TrackRadio: ImageVector get() = RawRadioDisc

    val RawAutomixBlocks: ImageVector
        get() {
            if (_rawAutomixBlocks != null) return _rawAutomixBlocks!!
            val builder = ImageVector.Builder(
                name = "RawAutomixBlocks",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.24f,
                stroke = null,
                strokeAlpha = 0.24f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(4.2f, 7.2f)
                curveToRelative(0f, -1.2f, 0.8f, -2f, 2f, -2f)
                horizontalLineToRelative(5.3f)
                curveToRelative(1f, 0f, 1.7f, 0.7f, 1.7f, 1.7f)
                verticalLineToRelative(10.2f)
                curveToRelative(0f, 1f, -0.7f, 1.7f, -1.7f, 1.7f)
                horizontalLineTo(6.2f)
                curveToRelative(-1.2f, 0f, -2f, -0.8f, -2f, -2f)
                verticalLineTo(7.2f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.56f,
                stroke = null,
                strokeAlpha = 0.56f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.8f, 6.9f)
                curveToRelative(0f, -1f, 0.7f, -1.7f, 1.7f, -1.7f)
                horizontalLineToRelative(5.3f)
                curveToRelative(1.2f, 0f, 2f, 0.8f, 2f, 2f)
                verticalLineToRelative(9.6f)
                curveToRelative(0f, 1.2f, -0.8f, 2f, -2f, 2f)
                horizontalLineToRelative(-5.3f)
                curveToRelative(-1f, 0f, -1.7f, -0.7f, -1.7f, -1.7f)
                verticalLineTo(6.9f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.7f, 9.5f)
                horizontalLineToRelative(3.3f)
                moveTo(13f, 14.5f)
                horizontalLineToRelative(3.3f)
            }
            return builder.build().also { _rawAutomixBlocks = it }
        }
    private var _rawAutomixBlocks: ImageVector? = null

    val RawDownloadTrack: ImageVector
        get() {
            if (_rawDownloadTrack != null) return _rawDownloadTrack!!
            val builder = ImageVector.Builder(
                name = "RawDownloadTrack",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                stroke = null,
                strokeAlpha = 0.18f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.6f, 12.1f)
                horizontalLineTo(17.4f)
                quadTo(20f, 12.1f, 20f, 14.7f)
                verticalLineTo(16.6f)
                quadTo(20f, 19.2f, 17.4f, 19.2f)
                horizontalLineTo(6.6f)
                quadTo(4f, 19.2f, 4f, 16.6f)
                verticalLineTo(14.7f)
                quadTo(4f, 12.1f, 6.6f, 12.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.8f)
                verticalLineToRelative(9.7f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveToRelative(8.8f, 11.6f)
                lineToRelative(3.2f, 3.2f)
                lineToRelative(3.2f, -3.2f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.4f, 16.2f)
                horizontalLineToRelative(9.2f)
            }
            return builder.build().also { _rawDownloadTrack = it }
        }
    private var _rawDownloadTrack: ImageVector? = null

    val RawEqualizerPills: ImageVector
        get() {
            if (_rawEqualizerPills != null) return _rawEqualizerPills!!
            val builder = ImageVector.Builder(
                name = "RawEqualizerPills",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.35f,
                stroke = null,
                strokeAlpha = 0.35f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.05f, 9.4f)
                horizontalLineTo(6.05f)
                quadTo(7.7f, 9.4f, 7.7f, 11.05f)
                verticalLineTo(16.95f)
                quadTo(7.7f, 18.6f, 6.05f, 18.6f)
                horizontalLineTo(6.05f)
                quadTo(4.4f, 18.6f, 4.4f, 16.95f)
                verticalLineTo(11.05f)
                quadTo(4.4f, 9.4f, 6.05f, 9.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.6f)
                horizontalLineTo(12f)
                quadTo(13.65f, 4.6f, 13.65f, 6.25f)
                verticalLineTo(16.95f)
                quadTo(13.65f, 18.6f, 12f, 18.6f)
                horizontalLineTo(12f)
                quadTo(10.35f, 18.6f, 10.35f, 16.95f)
                verticalLineTo(6.25f)
                quadTo(10.35f, 4.6f, 12f, 4.6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.62f,
                stroke = null,
                strokeAlpha = 0.62f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.95f, 7.2f)
                horizontalLineTo(17.95f)
                quadTo(19.6f, 7.2f, 19.6f, 8.85f)
                verticalLineTo(16.95f)
                quadTo(19.6f, 18.6f, 17.95f, 18.6f)
                horizontalLineTo(17.95f)
                quadTo(16.3f, 18.6f, 16.3f, 16.95f)
                verticalLineTo(8.85f)
                quadTo(16.3f, 7.2f, 17.95f, 7.2f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.05f, 5.15f)
                curveTo(6.9613f, 5.15f, 7.7f, 5.8887f, 7.7f, 6.8f)
                curveTo(7.7f, 7.7113f, 6.9613f, 8.45f, 6.05f, 8.45f)
                curveTo(5.1387f, 8.45f, 4.4f, 7.7113f, 4.4f, 6.8f)
                curveTo(4.4f, 5.8887f, 5.1387f, 5.15f, 6.05f, 5.15f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.62f,
                stroke = null,
                strokeAlpha = 0.62f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.95f, 3.25f)
                curveTo(18.8613f, 3.25f, 19.6f, 3.9887f, 19.6f, 4.9f)
                curveTo(19.6f, 5.8113f, 18.8613f, 6.55f, 17.95f, 6.55f)
                curveTo(17.0387f, 6.55f, 16.3f, 5.8113f, 16.3f, 4.9f)
                curveTo(16.3f, 3.9887f, 17.0387f, 3.25f, 17.95f, 3.25f)
                close()
            }
            return builder.build().also { _rawEqualizerPills = it }
        }
    private var _rawEqualizerPills: ImageVector? = null

    val RawHomeOrbit: ImageVector
        get() {
            if (_rawHomeOrbit != null) return _rawHomeOrbit!!
            val builder = ImageVector.Builder(
                name = "RawHomeOrbit",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                stroke = null,
                strokeAlpha = 0.18f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.1f, 10.8f)
                lineTo(12f, 6f)
                lineToRelative(5.9f, 4.8f)
                verticalLineToRelative(6.4f)
                curveToRelative(0f, 1.1f, -0.7f, 1.8f, -1.8f, 1.8f)
                horizontalLineTo(7.9f)
                curveToRelative(-1.1f, 0f, -1.8f, -0.7f, -1.8f, -1.8f)
                verticalLineToRelative(-6.4f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(7.2f, 10.7f)
                lineTo(12f, 6.9f)
                lineToRelative(4.8f, 3.8f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(9.9f, 18.8f)
                verticalLineToRelative(-3.4f)
                curveToRelative(0f, -1.1f, 0.8f, -1.9f, 2.1f, -1.9f)
                reflectiveCurveToRelative(2.1f, 0.8f, 2.1f, 1.9f)
                verticalLineToRelative(3.4f)
            }
            builder.path(
                fill = null,
                fillAlpha = 0.5f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 0.5f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(4.3f, 15.7f)
                curveToRelative(-1.1f, -2.3f, -0.7f, -5.2f, 1.1f, -7.2f)
                moveTo(19.7f, 15.7f)
                curveToRelative(1.1f, -2.3f, 0.7f, -5.2f, -1.1f, -7.2f)
            }
            return builder.build().also { _rawHomeOrbit = it }
        }
    private var _rawHomeOrbit: ImageVector? = null

    val RawImportArrowCards: ImageVector
        get() {
            if (_rawImportArrowCards != null) return _rawImportArrowCards!!
            val builder = ImageVector.Builder(
                name = "RawImportArrowCards",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                stroke = null,
                strokeAlpha = 0.18f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.4f, 5.1f)
                horizontalLineTo(7.2f)
                quadTo(10.2f, 5.1f, 10.2f, 8.1f)
                verticalLineTo(15.9f)
                quadTo(10.2f, 18.9f, 7.2f, 18.9f)
                horizontalLineTo(6.4f)
                quadTo(3.4f, 18.9f, 3.4f, 15.9f)
                verticalLineTo(8.1f)
                quadTo(3.4f, 5.1f, 6.4f, 5.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.34f,
                stroke = null,
                strokeAlpha = 0.34f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(16.8f, 5.1f)
                horizontalLineTo(17.6f)
                quadTo(20.6f, 5.1f, 20.6f, 8.1f)
                verticalLineTo(15.9f)
                quadTo(20.6f, 18.9f, 17.6f, 18.9f)
                horizontalLineTo(16.8f)
                quadTo(13.8f, 18.9f, 13.8f, 15.9f)
                verticalLineTo(8.1f)
                quadTo(13.8f, 5.1f, 16.8f, 5.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.1f, 12f)
                horizontalLineToRelative(8.6f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveToRelative(13.6f, 9.6f)
                lineToRelative(2.4f, 2.4f)
                lineToRelative(-2.4f, 2.4f)
            }
            return builder.build().also { _rawImportArrowCards = it }
        }
    private var _rawImportArrowCards: ImageVector? = null

    val RawLocalDisc: ImageVector
        get() {
            if (_rawLocalDisc != null) return _rawLocalDisc!!
            val builder = ImageVector.Builder(
                name = "RawLocalDisc",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.17f,
                stroke = null,
                strokeAlpha = 0.17f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(4.2f, 8.3f)
                curveToRelative(0f, -1.2f, 0.9f, -2.1f, 2.1f, -2.1f)
                horizontalLineToRelative(4.2f)
                lineToRelative(1.5f, 1.7f)
                horizontalLineToRelative(5.7f)
                curveToRelative(1.2f, 0f, 2.1f, 0.9f, 2.1f, 2.1f)
                verticalLineToRelative(6.4f)
                curveToRelative(0f, 1.2f, -0.9f, 2.1f, -2.1f, 2.1f)
                horizontalLineTo(6.3f)
                curveToRelative(-1.2f, 0f, -2.1f, -0.9f, -2.1f, -2.1f)
                verticalLineTo(8.3f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.35f,
                stroke = null,
                strokeAlpha = 0.35f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 9.9f)
                curveTo(13.8778f, 9.9f, 15.4f, 11.4222f, 15.4f, 13.3f)
                curveTo(15.4f, 15.1778f, 13.8778f, 16.7f, 12f, 16.7f)
                curveTo(10.1222f, 16.7f, 8.6f, 15.1778f, 8.6f, 13.3f)
                curveTo(8.6f, 11.4222f, 10.1222f, 9.9f, 12f, 9.9f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 12.1f)
                curveTo(12.6627f, 12.1f, 13.2f, 12.6373f, 13.2f, 13.3f)
                curveTo(13.2f, 13.9627f, 12.6627f, 14.5f, 12f, 14.5f)
                curveTo(11.3373f, 14.5f, 10.8f, 13.9627f, 10.8f, 13.3f)
                curveTo(10.8f, 12.6373f, 11.3373f, 12.1f, 12f, 12.1f)
                close()
            }
            return builder.build().also { _rawLocalDisc = it }
        }
    private var _rawLocalDisc: ImageVector? = null

    val RawPlaylistCards: ImageVector
        get() {
            if (_rawPlaylistCards != null) return _rawPlaylistCards!!
            val builder = ImageVector.Builder(
                name = "RawPlaylistCards",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                stroke = null,
                strokeAlpha = 0.18f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.2f, 4.7f)
                horizontalLineTo(14.8f)
                quadTo(17.8f, 4.7f, 17.8f, 7.7f)
                verticalLineTo(16.3f)
                quadTo(17.8f, 19.3f, 14.8f, 19.3f)
                horizontalLineTo(8.2f)
                quadTo(5.2f, 19.3f, 5.2f, 16.3f)
                verticalLineTo(7.7f)
                quadTo(5.2f, 4.7f, 8.2f, 4.7f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.38f,
                stroke = null,
                strokeAlpha = 0.38f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.1f, 6.5f)
                horizontalLineTo(16.1f)
                quadTo(19.1f, 6.5f, 19.1f, 9.5f)
                verticalLineTo(16.3f)
                quadTo(19.1f, 19.3f, 16.1f, 19.3f)
                horizontalLineTo(10.1f)
                quadTo(7.1f, 19.3f, 7.1f, 16.3f)
                verticalLineTo(9.5f)
                quadTo(7.1f, 6.5f, 10.1f, 6.5f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.4f, 10f)
                horizontalLineToRelative(5.4f)
                moveTo(10.4f, 13f)
                horizontalLineToRelative(4.3f)
                moveTo(10.4f, 16f)
                horizontalLineToRelative(2.8f)
            }
            return builder.build().also { _rawPlaylistCards = it }
        }
    private var _rawPlaylistCards: ImageVector? = null

    val RawPlaylistListDot: ImageVector
        get() {
            if (_rawPlaylistListDot != null) return _rawPlaylistListDot!!
            val builder = ImageVector.Builder(
                name = "RawPlaylistListDot",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.9f, 4.8f)
                horizontalLineTo(17.1f)
                quadTo(20.3f, 4.8f, 20.3f, 8f)
                verticalLineTo(16f)
                quadTo(20.3f, 19.2f, 17.1f, 19.2f)
                horizontalLineTo(6.9f)
                quadTo(3.7f, 19.2f, 3.7f, 16f)
                verticalLineTo(8f)
                quadTo(3.7f, 4.8f, 6.9f, 4.8f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.3f, 7.3f)
                curveTo(7.9627f, 7.3f, 8.5f, 7.8373f, 8.5f, 8.5f)
                curveTo(8.5f, 9.1627f, 7.9627f, 9.7f, 7.3f, 9.7f)
                curveTo(6.6373f, 9.7f, 6.1f, 9.1627f, 6.1f, 8.5f)
                curveTo(6.1f, 7.8373f, 6.6373f, 7.3f, 7.3f, 7.3f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.3f, 10.8f)
                curveTo(7.9627f, 10.8f, 8.5f, 11.3373f, 8.5f, 12f)
                curveTo(8.5f, 12.6627f, 7.9627f, 13.2f, 7.3f, 13.2f)
                curveTo(6.6373f, 13.2f, 6.1f, 12.6627f, 6.1f, 12f)
                curveTo(6.1f, 11.3373f, 6.6373f, 10.8f, 7.3f, 10.8f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.3f, 14.3f)
                curveTo(7.9627f, 14.3f, 8.5f, 14.8373f, 8.5f, 15.5f)
                curveTo(8.5f, 16.1627f, 7.9627f, 16.7f, 7.3f, 16.7f)
                curveTo(6.6373f, 16.7f, 6.1f, 16.1627f, 6.1f, 15.5f)
                curveTo(6.1f, 14.8373f, 6.6373f, 14.3f, 7.3f, 14.3f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.5f, 8.5f)
                horizontalLineToRelative(5.8f)
                moveTo(10.5f, 12f)
                horizontalLineToRelative(6.7f)
                moveTo(10.5f, 15.5f)
                horizontalLineToRelative(4.2f)
            }
            return builder.build().also { _rawPlaylistListDot = it }
        }
    private var _rawPlaylistListDot: ImageVector? = null

    val RawRadioDisc: ImageVector
        get() {
            if (_rawRadioDisc != null) return _rawRadioDisc!!
            val builder = ImageVector.Builder(
                name = "RawRadioDisc",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.6f)
                curveTo(16.0869f, 4.6f, 19.4f, 7.9131f, 19.4f, 12f)
                curveTo(19.4f, 16.0869f, 16.0869f, 19.4f, 12f, 19.4f)
                curveTo(7.9131f, 19.4f, 4.6f, 16.0869f, 4.6f, 12f)
                curveTo(4.6f, 7.9131f, 7.9131f, 4.6f, 12f, 4.6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 9.2f)
                curveTo(13.5464f, 9.2f, 14.8f, 10.4536f, 14.8f, 12f)
                curveTo(14.8f, 13.5464f, 13.5464f, 14.8f, 12f, 14.8f)
                curveTo(10.4536f, 14.8f, 9.2f, 13.5464f, 9.2f, 12f)
                curveTo(9.2f, 10.4536f, 10.4536f, 9.2f, 12f, 9.2f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.6f)
                curveToRelative(2.7f, 0f, 4.9f, 1.3f, 6.4f, 3.3f)
                moveTo(12f, 19.4f)
                curveToRelative(-2.7f, 0f, -4.9f, -1.3f, -6.4f, -3.3f)
            }
            return builder.build().also { _rawRadioDisc = it }
        }
    private var _rawRadioDisc: ImageVector? = null

    val RawSettingsPanel: ImageVector
        get() {
            if (_rawSettingsPanel != null) return _rawSettingsPanel!!
            val builder = ImageVector.Builder(
                name = "RawSettingsPanel",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.4f, 4.4f)
                horizontalLineTo(16.6f)
                quadTo(20f, 4.4f, 20f, 7.8f)
                verticalLineTo(16.2f)
                quadTo(20f, 19.6f, 16.6f, 19.6f)
                horizontalLineTo(7.4f)
                quadTo(4f, 19.6f, 4f, 16.2f)
                verticalLineTo(7.8f)
                quadTo(4f, 4.4f, 7.4f, 4.4f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 0.55f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 0.55f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8f, 9f)
                horizontalLineToRelative(8f)
                moveTo(8f, 15f)
                horizontalLineToRelative(8f)
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10f, 6.9f)
                curveTo(11.1598f, 6.9f, 12.1f, 7.8402f, 12.1f, 9f)
                curveTo(12.1f, 10.1598f, 11.1598f, 11.1f, 10f, 11.1f)
                curveTo(8.8402f, 11.1f, 7.9f, 10.1598f, 7.9f, 9f)
                curveTo(7.9f, 7.8402f, 8.8402f, 6.9f, 10f, 6.9f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(14f, 12.9f)
                curveTo(15.1598f, 12.9f, 16.1f, 13.8402f, 16.1f, 15f)
                curveTo(16.1f, 16.1598f, 15.1598f, 17.1f, 14f, 17.1f)
                curveTo(12.8402f, 17.1f, 11.9f, 16.1598f, 11.9f, 15f)
                curveTo(11.9f, 13.8402f, 12.8402f, 12.9f, 14f, 12.9f)
                close()
            }
            return builder.build().also { _rawSettingsPanel = it }
        }
    private var _rawSettingsPanel: ImageVector? = null

    val RawAddV4: ImageVector
        get() {
            if (_rawAddV4 != null) return _rawAddV4!!
            val builder = ImageVector.Builder(
                name = "RawAddV4",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.12f,
                stroke = null,
                strokeAlpha = 0.12f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.2f, 6f)
                horizontalLineTo(15.8f)
                quadTo(19.8f, 6f, 19.8f, 10f)
                verticalLineTo(14f)
                quadTo(19.8f, 18f, 15.8f, 18f)
                horizontalLineTo(8.2f)
                quadTo(4.2f, 18f, 4.2f, 14f)
                verticalLineTo(10f)
                quadTo(4.2f, 6f, 8.2f, 6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.55f,
                stroke = null,
                strokeAlpha = 0.55f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(18.3f, 4.4f)
                curveTo(19.2389f, 4.4f, 20f, 5.1611f, 20f, 6.1f)
                curveTo(20f, 7.0389f, 19.2389f, 7.8f, 18.3f, 7.8f)
                curveTo(17.3611f, 7.8f, 16.6f, 7.0389f, 16.6f, 6.1f)
                curveTo(16.6f, 5.1611f, 17.3611f, 4.4f, 18.3f, 4.4f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 5.6f)
                verticalLineToRelative(12.8f)
                moveTo(5.6f, 12f)
                horizontalLineToRelative(12.8f)
            }
            return builder.build().also { _rawAddV4 = it }
        }
    private var _rawAddV4: ImageVector? = null

    val RawAlbumV4: ImageVector
        get() {
            if (_rawAlbumV4 != null) return _rawAlbumV4!!
            val builder = ImageVector.Builder(
                name = "RawAlbumV4",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.12f,
                stroke = null,
                strokeAlpha = 0.12f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.2f, 6f)
                horizontalLineTo(15.8f)
                quadTo(19.8f, 6f, 19.8f, 10f)
                verticalLineTo(14f)
                quadTo(19.8f, 18f, 15.8f, 18f)
                horizontalLineTo(8.2f)
                quadTo(4.2f, 18f, 4.2f, 14f)
                verticalLineTo(10f)
                quadTo(4.2f, 6f, 8.2f, 6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.55f,
                stroke = null,
                strokeAlpha = 0.55f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(18.3f, 4.4f)
                curveTo(19.2389f, 4.4f, 20f, 5.1611f, 20f, 6.1f)
                curveTo(20f, 7.0389f, 19.2389f, 7.8f, 18.3f, 7.8f)
                curveTo(17.3611f, 7.8f, 16.6f, 7.0389f, 16.6f, 6.1f)
                curveTo(16.6f, 5.1611f, 17.3611f, 4.4f, 18.3f, 4.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.4f, 4.4f)
                horizontalLineTo(15.6f)
                quadTo(19.6f, 4.4f, 19.6f, 8.4f)
                verticalLineTo(15.6f)
                quadTo(19.6f, 19.6f, 15.6f, 19.6f)
                horizontalLineTo(8.4f)
                quadTo(4.4f, 19.6f, 4.4f, 15.6f)
                verticalLineTo(8.4f)
                quadTo(4.4f, 4.4f, 8.4f, 4.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.45f,
                stroke = null,
                strokeAlpha = 0.45f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 6.9f)
                curveTo(14.8167f, 6.9f, 17.1f, 9.1833f, 17.1f, 12f)
                curveTo(17.1f, 14.8167f, 14.8167f, 17.1f, 12f, 17.1f)
                curveTo(9.1833f, 17.1f, 6.9f, 14.8167f, 6.9f, 12f)
                curveTo(6.9f, 9.1833f, 9.1833f, 6.9f, 12f, 6.9f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 10.5f)
                curveTo(12.8284f, 10.5f, 13.5f, 11.1716f, 13.5f, 12f)
                curveTo(13.5f, 12.8284f, 12.8284f, 13.5f, 12f, 13.5f)
                curveTo(11.1716f, 13.5f, 10.5f, 12.8284f, 10.5f, 12f)
                curveTo(10.5f, 11.1716f, 11.1716f, 10.5f, 12f, 10.5f)
                close()
            }
            return builder.build().also { _rawAlbumV4 = it }
        }
    private var _rawAlbumV4: ImageVector? = null

    val RawAutomixV1: ImageVector
        get() {
            if (_rawAutomixV1 != null) return _rawAutomixV1!!
            val builder = ImageVector.Builder(
                name = "RawAutomixV1",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.24f,
                stroke = null,
                strokeAlpha = 0.24f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(4.2f, 7.2f)
                curveToRelative(0f, -1.2f, 0.8f, -2f, 2f, -2f)
                horizontalLineToRelative(5.3f)
                curveToRelative(1f, 0f, 1.7f, 0.7f, 1.7f, 1.7f)
                verticalLineToRelative(10.2f)
                curveToRelative(0f, 1f, -0.7f, 1.7f, -1.7f, 1.7f)
                horizontalLineTo(6.2f)
                curveToRelative(-1.2f, 0f, -2f, -0.8f, -2f, -2f)
                verticalLineTo(7.2f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.56f,
                stroke = null,
                strokeAlpha = 0.56f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.8f, 6.9f)
                curveToRelative(0f, -1f, 0.7f, -1.7f, 1.7f, -1.7f)
                horizontalLineToRelative(5.3f)
                curveToRelative(1.2f, 0f, 2f, 0.8f, 2f, 2f)
                verticalLineToRelative(9.6f)
                curveToRelative(0f, 1.2f, -0.8f, 2f, -2f, 2f)
                horizontalLineToRelative(-5.3f)
                curveToRelative(-1f, 0f, -1.7f, -0.7f, -1.7f, -1.7f)
                verticalLineTo(6.9f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.7f, 9.5f)
                horizontalLineToRelative(3.3f)
                moveTo(13f, 14.5f)
                horizontalLineToRelative(3.3f)
            }
            return builder.build().also { _rawAutomixV1 = it }
        }
    private var _rawAutomixV1: ImageVector? = null

    val RawBackV3: ImageVector
        get() {
            if (_rawBackV3 != null) return _rawBackV3!!
            val builder = ImageVector.Builder(
                name = "RawBackV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.3f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(15.8f, 5.5f)
                lineTo(9.3f, 12f)
                lineToRelative(6.5f, 6.5f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.3f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10f, 12f)
                horizontalLineToRelative(9f)
            }
            return builder.build().also { _rawBackV3 = it }
        }
    private var _rawBackV3: ImageVector? = null

    val RawCastDevicesV4: ImageVector
        get() {
            if (_rawCastDevicesV4 != null) return _rawCastDevicesV4!!
            val builder = ImageVector.Builder(
                name = "RawCastDevicesV4",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.12f,
                stroke = null,
                strokeAlpha = 0.12f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.2f, 6f)
                horizontalLineTo(15.8f)
                quadTo(19.8f, 6f, 19.8f, 10f)
                verticalLineTo(14f)
                quadTo(19.8f, 18f, 15.8f, 18f)
                horizontalLineTo(8.2f)
                quadTo(4.2f, 18f, 4.2f, 14f)
                verticalLineTo(10f)
                quadTo(4.2f, 6f, 8.2f, 6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.55f,
                stroke = null,
                strokeAlpha = 0.55f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(18.3f, 4.4f)
                curveTo(19.2389f, 4.4f, 20f, 5.1611f, 20f, 6.1f)
                curveTo(20f, 7.0389f, 19.2389f, 7.8f, 18.3f, 7.8f)
                curveTo(17.3611f, 7.8f, 16.6f, 7.0389f, 16.6f, 6.1f)
                curveTo(16.6f, 5.1611f, 17.3611f, 4.4f, 18.3f, 4.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.8f, 6.2f)
                horizontalLineTo(17.2f)
                quadTo(19.8f, 6.2f, 19.8f, 8.8f)
                verticalLineTo(15f)
                quadTo(19.8f, 17.6f, 17.2f, 17.6f)
                horizontalLineTo(6.8f)
                quadTo(4.2f, 17.6f, 4.2f, 15f)
                verticalLineTo(8.8f)
                quadTo(4.2f, 6.2f, 6.8f, 6.2f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5.3f, 14.8f)
                curveToRelative(2.2f, 0f, 3.9f, 1.7f, 3.9f, 3.9f)
                moveTo(5.3f, 11.2f)
                curveToRelative(4.2f, 0f, 7.5f, 3.3f, 7.5f, 7.5f)
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5.5f, 17.2f)
                curveTo(6.1075f, 17.2f, 6.6f, 17.6925f, 6.6f, 18.3f)
                curveTo(6.6f, 18.9075f, 6.1075f, 19.4f, 5.5f, 19.4f)
                curveTo(4.8925f, 19.4f, 4.4f, 18.9075f, 4.4f, 18.3f)
                curveTo(4.4f, 17.6925f, 4.8925f, 17.2f, 5.5f, 17.2f)
                close()
            }
            return builder.build().also { _rawCastDevicesV4 = it }
        }
    private var _rawCastDevicesV4: ImageVector? = null

    val RawChatV4: ImageVector
        get() {
            if (_rawChatV4 != null) return _rawChatV4!!
            val builder = ImageVector.Builder(
                name = "RawChatV4",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.12f,
                stroke = null,
                strokeAlpha = 0.12f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.2f, 6f)
                horizontalLineTo(15.8f)
                quadTo(19.8f, 6f, 19.8f, 10f)
                verticalLineTo(14f)
                quadTo(19.8f, 18f, 15.8f, 18f)
                horizontalLineTo(8.2f)
                quadTo(4.2f, 18f, 4.2f, 14f)
                verticalLineTo(10f)
                quadTo(4.2f, 6f, 8.2f, 6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.55f,
                stroke = null,
                strokeAlpha = 0.55f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(18.3f, 4.4f)
                curveTo(19.2389f, 4.4f, 20f, 5.1611f, 20f, 6.1f)
                curveTo(20f, 7.0389f, 19.2389f, 7.8f, 18.3f, 7.8f)
                curveTo(17.3611f, 7.8f, 16.6f, 7.0389f, 16.6f, 6.1f)
                curveTo(16.6f, 5.1611f, 17.3611f, 4.4f, 18.3f, 4.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                stroke = null,
                strokeAlpha = 0.18f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5f, 7.1f)
                curveToRelative(0f, -1.2f, 0.9f, -2.1f, 2.1f, -2.1f)
                horizontalLineToRelative(9.8f)
                curveToRelative(1.2f, 0f, 2.1f, 0.9f, 2.1f, 2.1f)
                verticalLineToRelative(6.6f)
                curveToRelative(0f, 1.2f, -0.9f, 2.1f, -2.1f, 2.1f)
                horizontalLineToRelative(-4.2f)
                lineToRelative(-4f, 3.2f)
                verticalLineToRelative(-3.2f)
                horizontalLineTo(7.1f)
                curveToRelative(-1.2f, 0f, -2.1f, -0.9f, -2.1f, -2.1f)
                verticalLineTo(7.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 9.2f)
                horizontalLineToRelative(7.4f)
                moveTo(8.3f, 12.3f)
                horizontalLineToRelative(4.8f)
            }
            return builder.build().also { _rawChatV4 = it }
        }
    private var _rawChatV4: ImageVector? = null

    val RawCheckV3: ImageVector
        get() {
            if (_rawCheckV3 != null) return _rawCheckV3!!
            val builder = ImageVector.Builder(
                name = "RawCheckV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveToRelative(6.1f, 12.2f)
                lineToRelative(3.8f, 3.8f)
                lineToRelative(8f, -8.4f)
            }
            return builder.build().also { _rawCheckV3 = it }
        }
    private var _rawCheckV3: ImageVector? = null

    val RawChevronRightV3: ImageVector
        get() {
            if (_rawChevronRightV3 != null) return _rawChevronRightV3!!
            val builder = ImageVector.Builder(
                name = "RawChevronRightV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveToRelative(9.4f, 6.2f)
                lineToRelative(5.8f, 5.8f)
                lineToRelative(-5.8f, 5.8f)
            }
            return builder.build().also { _rawChevronRightV3 = it }
        }
    private var _rawChevronRightV3: ImageVector? = null

    val RawCloseV3: ImageVector
        get() {
            if (_rawCloseV3 != null) return _rawCloseV3!!
            val builder = ImageVector.Builder(
                name = "RawCloseV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveToRelative(7.4f, 7.4f)
                lineToRelative(9.2f, 9.2f)
                moveTo(16.6f, 7.4f)
                lineToRelative(-9.2f, 9.2f)
            }
            return builder.build().also { _rawCloseV3 = it }
        }
    private var _rawCloseV3: ImageVector? = null

    val RawDownloadTrackV1: ImageVector
        get() {
            if (_rawDownloadTrackV1 != null) return _rawDownloadTrackV1!!
            val builder = ImageVector.Builder(
                name = "RawDownloadTrackV1",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                stroke = null,
                strokeAlpha = 0.18f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.7f, 12.1f)
                horizontalLineTo(17.3f)
                quadTo(19.9f, 12.1f, 19.9f, 14.7f)
                verticalLineTo(16.5f)
                quadTo(19.9f, 19.1f, 17.3f, 19.1f)
                horizontalLineTo(6.7f)
                quadTo(4.1f, 19.1f, 4.1f, 16.5f)
                verticalLineTo(14.7f)
                quadTo(4.1f, 12.1f, 6.7f, 12.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.7f)
                verticalLineToRelative(9.6f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveToRelative(8.8f, 11.5f)
                lineToRelative(3.2f, 3.2f)
                lineToRelative(3.2f, -3.2f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.5f, 16.2f)
                horizontalLineToRelative(9f)
            }
            return builder.build().also { _rawDownloadTrackV1 = it }
        }
    private var _rawDownloadTrackV1: ImageVector? = null

    val RawEditV3: ImageVector
        get() {
            if (_rawEditV3 != null) return _rawEditV3!!
            val builder = ImageVector.Builder(
                name = "RawEditV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.28f,
                stroke = null,
                strokeAlpha = 0.28f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.2f, 16.8f)
                lineTo(7f, 13f)
                lineToRelative(7.9f, -7.9f)
                curveToRelative(0.8f, -0.8f, 2f, -0.8f, 2.8f, 0f)
                lineToRelative(1.2f, 1.2f)
                curveToRelative(0.8f, 0.8f, 0.8f, 2f, 0f, 2.8f)
                lineTo(11f, 17f)
                lineToRelative(-3.8f, 0.8f)
                curveToRelative(-0.6f, 0.1f, -1.1f, -0.4f, -1f, -1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveToRelative(13.6f, 6.4f)
                lineToRelative(4f, 4f)
            }
            return builder.build().also { _rawEditV3 = it }
        }
    private var _rawEditV3: ImageVector? = null

    val RawEqualizerV3: ImageVector
        get() {
            if (_rawEqualizerV3 != null) return _rawEqualizerV3!!
            val builder = ImageVector.Builder(
                name = "RawEqualizerV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.35f,
                stroke = null,
                strokeAlpha = 0.35f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.05f, 9.4f)
                horizontalLineTo(6.05f)
                quadTo(7.7f, 9.4f, 7.7f, 11.05f)
                verticalLineTo(16.95f)
                quadTo(7.7f, 18.6f, 6.05f, 18.6f)
                horizontalLineTo(6.05f)
                quadTo(4.4f, 18.6f, 4.4f, 16.95f)
                verticalLineTo(11.05f)
                quadTo(4.4f, 9.4f, 6.05f, 9.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.6f)
                horizontalLineTo(12f)
                quadTo(13.65f, 4.6f, 13.65f, 6.25f)
                verticalLineTo(16.95f)
                quadTo(13.65f, 18.6f, 12f, 18.6f)
                horizontalLineTo(12f)
                quadTo(10.35f, 18.6f, 10.35f, 16.95f)
                verticalLineTo(6.25f)
                quadTo(10.35f, 4.6f, 12f, 4.6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.62f,
                stroke = null,
                strokeAlpha = 0.62f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.95f, 7.2f)
                horizontalLineTo(17.95f)
                quadTo(19.6f, 7.2f, 19.6f, 8.85f)
                verticalLineTo(16.95f)
                quadTo(19.6f, 18.6f, 17.95f, 18.6f)
                horizontalLineTo(17.95f)
                quadTo(16.3f, 18.6f, 16.3f, 16.95f)
                verticalLineTo(8.85f)
                quadTo(16.3f, 7.2f, 17.95f, 7.2f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.05f, 5.15f)
                curveTo(6.9613f, 5.15f, 7.7f, 5.8887f, 7.7f, 6.8f)
                curveTo(7.7f, 7.7113f, 6.9613f, 8.45f, 6.05f, 8.45f)
                curveTo(5.1387f, 8.45f, 4.4f, 7.7113f, 4.4f, 6.8f)
                curveTo(4.4f, 5.8887f, 5.1387f, 5.15f, 6.05f, 5.15f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.62f,
                stroke = null,
                strokeAlpha = 0.62f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.95f, 3.25f)
                curveTo(18.8613f, 3.25f, 19.6f, 3.9887f, 19.6f, 4.9f)
                curveTo(19.6f, 5.8113f, 18.8613f, 6.55f, 17.95f, 6.55f)
                curveTo(17.0387f, 6.55f, 16.3f, 5.8113f, 16.3f, 4.9f)
                curveTo(16.3f, 3.9887f, 17.0387f, 3.25f, 17.95f, 3.25f)
                close()
            }
            return builder.build().also { _rawEqualizerV3 = it }
        }
    private var _rawEqualizerV3: ImageVector? = null

    val RawErrorV3: ImageVector
        get() {
            if (_rawErrorV3 != null) return _rawErrorV3!!
            val builder = ImageVector.Builder(
                name = "RawErrorV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                stroke = null,
                strokeAlpha = 0.18f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.4f)
                lineTo(20f, 18.2f)
                horizontalLineTo(4f)
                lineTo(12f, 4.4f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 9f)
                verticalLineToRelative(4.6f)
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 15.1f)
                curveTo(12.6075f, 15.1f, 13.1f, 15.5925f, 13.1f, 16.2f)
                curveTo(13.1f, 16.8075f, 12.6075f, 17.3f, 12f, 17.3f)
                curveTo(11.3925f, 17.3f, 10.9f, 16.8075f, 10.9f, 16.2f)
                curveTo(10.9f, 15.5925f, 11.3925f, 15.1f, 12f, 15.1f)
                close()
            }
            return builder.build().also { _rawErrorV3 = it }
        }
    private var _rawErrorV3: ImageVector? = null

    val RawHeadphonesV2: ImageVector
        get() {
            if (_rawHeadphonesV2 != null) return _rawHeadphonesV2!!
            val builder = ImageVector.Builder(
                name = "RawHeadphonesV2",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 3.7f)
                curveTo(16.584f, 3.7f, 20.3f, 7.416f, 20.3f, 12f)
                curveTo(20.3f, 16.584f, 16.584f, 20.3f, 12f, 20.3f)
                curveTo(7.416f, 20.3f, 3.7f, 16.584f, 3.7f, 12f)
                curveTo(3.7f, 7.416f, 7.416f, 3.7f, 12f, 3.7f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5.2f, 13.2f)
                verticalLineToRelative(-1.8f)
                curveToRelative(0f, -3.9f, 2.8f, -6.8f, 6.8f, -6.8f)
                reflectiveCurveToRelative(6.8f, 2.9f, 6.8f, 6.8f)
                verticalLineToRelative(1.8f)
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6f, 12f)
                horizontalLineTo(6f)
                quadTo(7.8f, 12f, 7.8f, 13.8f)
                verticalLineTo(16.2f)
                quadTo(7.8f, 18f, 6f, 18f)
                horizontalLineTo(6f)
                quadTo(4.2f, 18f, 4.2f, 16.2f)
                verticalLineTo(13.8f)
                quadTo(4.2f, 12f, 6f, 12f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(18f, 12f)
                horizontalLineTo(18f)
                quadTo(19.8f, 12f, 19.8f, 13.8f)
                verticalLineTo(16.2f)
                quadTo(19.8f, 18f, 18f, 18f)
                horizontalLineTo(18f)
                quadTo(16.2f, 18f, 16.2f, 16.2f)
                verticalLineTo(13.8f)
                quadTo(16.2f, 12f, 18f, 12f)
                close()
            }
            return builder.build().also { _rawHeadphonesV2 = it }
        }
    private var _rawHeadphonesV2: ImageVector? = null

    val RawHeartV2: ImageVector
        get() {
            if (_rawHeartV2 != null) return _rawHeartV2!!
            val builder = ImageVector.Builder(
                name = "RawHeartV2",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 3.7f)
                curveTo(16.584f, 3.7f, 20.3f, 7.416f, 20.3f, 12f)
                curveTo(20.3f, 16.584f, 16.584f, 20.3f, 12f, 20.3f)
                curveTo(7.416f, 20.3f, 3.7f, 16.584f, 3.7f, 12f)
                curveTo(3.7f, 7.416f, 7.416f, 3.7f, 12f, 3.7f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 18.8f)
                curveToRelative(-4.3f, -2.9f, -6.8f, -5.3f, -6.8f, -8.3f)
                curveToRelative(0f, -2.1f, 1.5f, -3.6f, 3.5f, -3.6f)
                curveToRelative(1.3f, 0f, 2.4f, 0.6f, 3.3f, 1.7f)
                curveToRelative(0.9f, -1.1f, 2f, -1.7f, 3.3f, -1.7f)
                curveToRelative(2f, 0f, 3.5f, 1.5f, 3.5f, 3.6f)
                curveToRelative(0f, 3f, -2.5f, 5.4f, -6.8f, 8.3f)
                close()
            }
            return builder.build().also { _rawHeartV2 = it }
        }
    private var _rawHeartV2: ImageVector? = null

    val RawHistoryV2: ImageVector
        get() {
            if (_rawHistoryV2 != null) return _rawHistoryV2!!
            val builder = ImageVector.Builder(
                name = "RawHistoryV2",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 3.7f)
                curveTo(16.584f, 3.7f, 20.3f, 7.416f, 20.3f, 12f)
                curveTo(20.3f, 16.584f, 16.584f, 20.3f, 12f, 20.3f)
                curveTo(7.416f, 20.3f, 3.7f, 16.584f, 3.7f, 12f)
                curveTo(3.7f, 7.416f, 7.416f, 3.7f, 12f, 3.7f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.9f)
                curveTo(15.9212f, 4.9f, 19.1f, 8.0788f, 19.1f, 12f)
                curveTo(19.1f, 15.9212f, 15.9212f, 19.1f, 12f, 19.1f)
                curveTo(8.0788f, 19.1f, 4.9f, 15.9212f, 4.9f, 12f)
                curveTo(4.9f, 8.0788f, 8.0788f, 4.9f, 12f, 4.9f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 7.8f)
                verticalLineToRelative(4.5f)
                lineToRelative(3.4f, 2f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6.1f, 7.7f)
                horizontalLineTo(3.9f)
                verticalLineTo(5.5f)
            }
            return builder.build().also { _rawHistoryV2 = it }
        }
    private var _rawHistoryV2: ImageVector? = null

    val RawLocalAudioV1: ImageVector
        get() {
            if (_rawLocalAudioV1 != null) return _rawLocalAudioV1!!
            val builder = ImageVector.Builder(
                name = "RawLocalAudioV1",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.17f,
                stroke = null,
                strokeAlpha = 0.17f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(4.2f, 8.3f)
                curveToRelative(0f, -1.2f, 0.9f, -2.1f, 2.1f, -2.1f)
                horizontalLineToRelative(4.2f)
                lineTo(12f, 7.9f)
                horizontalLineToRelative(5.7f)
                curveToRelative(1.2f, 0f, 2.1f, 0.9f, 2.1f, 2.1f)
                verticalLineToRelative(6.4f)
                curveToRelative(0f, 1.2f, -0.9f, 2.1f, -2.1f, 2.1f)
                horizontalLineTo(6.3f)
                curveToRelative(-1.2f, 0f, -2.1f, -0.9f, -2.1f, -2.1f)
                verticalLineTo(8.3f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.35f,
                stroke = null,
                strokeAlpha = 0.35f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 9.9f)
                curveTo(13.8778f, 9.9f, 15.4f, 11.4222f, 15.4f, 13.3f)
                curveTo(15.4f, 15.1778f, 13.8778f, 16.7f, 12f, 16.7f)
                curveTo(10.1222f, 16.7f, 8.6f, 15.1778f, 8.6f, 13.3f)
                curveTo(8.6f, 11.4222f, 10.1222f, 9.9f, 12f, 9.9f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 12.1f)
                curveTo(12.6627f, 12.1f, 13.2f, 12.6373f, 13.2f, 13.3f)
                curveTo(13.2f, 13.9627f, 12.6627f, 14.5f, 12f, 14.5f)
                curveTo(11.3373f, 14.5f, 10.8f, 13.9627f, 10.8f, 13.3f)
                curveTo(10.8f, 12.6373f, 11.3373f, 12.1f, 12f, 12.1f)
                close()
            }
            return builder.build().also { _rawLocalAudioV1 = it }
        }
    private var _rawLocalAudioV1: ImageVector? = null

    val RawMoreV4: ImageVector
        get() {
            if (_rawMoreV4 != null) return _rawMoreV4!!
            val builder = ImageVector.Builder(
                name = "RawMoreV4",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.12f,
                stroke = null,
                strokeAlpha = 0.12f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.2f, 6f)
                horizontalLineTo(15.8f)
                quadTo(19.8f, 6f, 19.8f, 10f)
                verticalLineTo(14f)
                quadTo(19.8f, 18f, 15.8f, 18f)
                horizontalLineTo(8.2f)
                quadTo(4.2f, 18f, 4.2f, 14f)
                verticalLineTo(10f)
                quadTo(4.2f, 6f, 8.2f, 6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.55f,
                stroke = null,
                strokeAlpha = 0.55f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(18.3f, 4.4f)
                curveTo(19.2389f, 4.4f, 20f, 5.1611f, 20f, 6.1f)
                curveTo(20f, 7.0389f, 19.2389f, 7.8f, 18.3f, 7.8f)
                curveTo(17.3611f, 7.8f, 16.6f, 7.0389f, 16.6f, 6.1f)
                curveTo(16.6f, 5.1611f, 17.3611f, 4.4f, 18.3f, 4.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.5f, 10.2f)
                curveTo(7.4941f, 10.2f, 8.3f, 11.0059f, 8.3f, 12f)
                curveTo(8.3f, 12.9941f, 7.4941f, 13.8f, 6.5f, 13.8f)
                curveTo(5.5059f, 13.8f, 4.7f, 12.9941f, 4.7f, 12f)
                curveTo(4.7f, 11.0059f, 5.5059f, 10.2f, 6.5f, 10.2f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 10.2f)
                curveTo(12.9941f, 10.2f, 13.8f, 11.0059f, 13.8f, 12f)
                curveTo(13.8f, 12.9941f, 12.9941f, 13.8f, 12f, 13.8f)
                curveTo(11.0059f, 13.8f, 10.2f, 12.9941f, 10.2f, 12f)
                curveTo(10.2f, 11.0059f, 11.0059f, 10.2f, 12f, 10.2f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.5f, 10.2f)
                curveTo(18.4941f, 10.2f, 19.3f, 11.0059f, 19.3f, 12f)
                curveTo(19.3f, 12.9941f, 18.4941f, 13.8f, 17.5f, 13.8f)
                curveTo(16.5059f, 13.8f, 15.7f, 12.9941f, 15.7f, 12f)
                curveTo(15.7f, 11.0059f, 16.5059f, 10.2f, 17.5f, 10.2f)
                close()
            }
            return builder.build().also { _rawMoreV4 = it }
        }
    private var _rawMoreV4: ImageVector? = null

    val RawMusicAppOpenV3: ImageVector
        get() {
            if (_rawMusicAppOpenV3 != null) return _rawMusicAppOpenV3!!
            val builder = ImageVector.Builder(
                name = "RawMusicAppOpenV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.2f,
                stroke = null,
                strokeAlpha = 0.2f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8f, 5f)
                horizontalLineTo(12.4f)
                quadTo(15.4f, 5f, 15.4f, 8f)
                verticalLineTo(16f)
                quadTo(15.4f, 19f, 12.4f, 19f)
                horizontalLineTo(8f)
                quadTo(5f, 19f, 5f, 16f)
                verticalLineTo(8f)
                quadTo(5f, 5f, 8f, 5f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.4f, 9.1f)
                verticalLineToRelative(5.2f)
                curveToRelative(0f, 1.1f, -0.8f, 1.8f, -1.8f, 1.8f)
                reflectiveCurveToRelative(-1.8f, -0.6f, -1.8f, -1.5f)
                reflectiveCurveToRelative(0.8f, -1.6f, 1.8f, -1.6f)
                curveToRelative(0.6f, 0f, 1.1f, 0.1f, 1.8f, 0.5f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(13.8f, 5f)
                horizontalLineToRelative(5.2f)
                verticalLineToRelative(5.2f)
                moveTo(18.7f, 5.3f)
                lineToRelative(-6.1f, 6.1f)
            }
            return builder.build().also { _rawMusicAppOpenV3 = it }
        }
    private var _rawMusicAppOpenV3: ImageVector? = null

    val RawMusicNoteV1: ImageVector
        get() {
            if (_rawMusicNoteV1 != null) return _rawMusicNoteV1!!
            val builder = ImageVector.Builder(
                name = "RawMusicNoteV1",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(14.8f, 5.3f)
                verticalLineToRelative(9.8f)
                curveToRelative(0f, 1.5f, -1.2f, 2.6f, -2.8f, 2.6f)
                reflectiveCurveToRelative(-2.8f, -0.9f, -2.8f, -2.2f)
                reflectiveCurveToRelative(1.2f, -2.2f, 2.8f, -2.2f)
                curveToRelative(0.9f, 0f, 1.8f, 0.3f, 2.8f, 0.9f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(14.8f, 5.3f)
                curveToRelative(1.8f, 0.2f, 3.1f, 0.8f, 4.2f, 1.8f)
            }
            return builder.build().also { _rawMusicNoteV1 = it }
        }
    private var _rawMusicNoteV1: ImageVector? = null

    val RawNextV1: ImageVector
        get() {
            if (_rawNextV1 != null) return _rawNextV1!!
            val builder = ImageVector.Builder(
                name = "RawNextV1",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.8f, 7f)
                horizontalLineTo(18.1f)
                quadTo(19.2f, 7f, 19.2f, 8.1f)
                verticalLineTo(15.9f)
                quadTo(19.2f, 17f, 18.1f, 17f)
                horizontalLineTo(17.8f)
                quadTo(16.7f, 17f, 16.7f, 15.9f)
                verticalLineTo(8.1f)
                quadTo(16.7f, 7f, 17.8f, 7f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6f, 7.9f)
                curveToRelative(0f, -0.8f, 0.9f, -1.3f, 1.6f, -0.9f)
                lineToRelative(6.7f, 4.1f)
                curveToRelative(0.7f, 0.4f, 0.7f, 1.4f, 0f, 1.8f)
                lineTo(7.6f, 17f)
                curveToRelative(-0.7f, 0.4f, -1.6f, -0.1f, -1.6f, -0.9f)
                verticalLineTo(7.9f)
                close()
            }
            return builder.build().also { _rawNextV1 = it }
        }
    private var _rawNextV1: ImageVector? = null

    val RawPauseV1: ImageVector
        get() {
            if (_rawPauseV1 != null) return _rawPauseV1!!
            val builder = ImageVector.Builder(
                name = "RawPauseV1",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.8f, 6.5f)
                horizontalLineTo(9.5f)
                quadTo(10.7f, 6.5f, 10.7f, 7.7f)
                verticalLineTo(16.3f)
                quadTo(10.7f, 17.5f, 9.5f, 17.5f)
                horizontalLineTo(8.8f)
                quadTo(7.6f, 17.5f, 7.6f, 16.3f)
                verticalLineTo(7.7f)
                quadTo(7.6f, 6.5f, 8.8f, 6.5f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(14.5f, 6.5f)
                horizontalLineTo(15.2f)
                quadTo(16.4f, 6.5f, 16.4f, 7.7f)
                verticalLineTo(16.3f)
                quadTo(16.4f, 17.5f, 15.2f, 17.5f)
                horizontalLineTo(14.5f)
                quadTo(13.3f, 17.5f, 13.3f, 16.3f)
                verticalLineTo(7.7f)
                quadTo(13.3f, 6.5f, 14.5f, 6.5f)
                close()
            }
            return builder.build().also { _rawPauseV1 = it }
        }
    private var _rawPauseV1: ImageVector? = null

    val RawPersonV3: ImageVector
        get() {
            if (_rawPersonV3 != null) return _rawPersonV3!!
            val builder = ImageVector.Builder(
                name = "RawPersonV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.55f,
                stroke = null,
                strokeAlpha = 0.55f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 5.1f)
                curveTo(13.8778f, 5.1f, 15.4f, 6.6222f, 15.4f, 8.5f)
                curveTo(15.4f, 10.3778f, 13.8778f, 11.9f, 12f, 11.9f)
                curveTo(10.1222f, 11.9f, 8.6f, 10.3778f, 8.6f, 8.5f)
                curveTo(8.6f, 6.6222f, 10.1222f, 5.1f, 12f, 5.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.28f,
                stroke = null,
                strokeAlpha = 0.28f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5.4f, 19f)
                curveToRelative(0.8f, -3.2f, 3.1f, -5f, 6.6f, -5f)
                reflectiveCurveToRelative(5.8f, 1.8f, 6.6f, 5f)
            }
            return builder.build().also { _rawPersonV3 = it }
        }
    private var _rawPersonV3: ImageVector? = null

    val RawPlayV1: ImageVector
        get() {
            if (_rawPlayV1 != null) return _rawPlayV1!!
            val builder = ImageVector.Builder(
                name = "RawPlayV1",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.8f, 7.3f)
                curveToRelative(0f, -0.9f, 1f, -1.5f, 1.8f, -1f)
                lineToRelative(7.4f, 4.5f)
                curveToRelative(0.8f, 0.5f, 0.8f, 1.8f, 0f, 2.3f)
                lineToRelative(-7.4f, 4.6f)
                curveToRelative(-0.8f, 0.5f, -1.8f, -0.1f, -1.8f, -1f)
                verticalLineTo(7.3f)
                close()
            }
            return builder.build().also { _rawPlayV1 = it }
        }
    private var _rawPlayV1: ImageVector? = null

    val RawPlaylistAddV3: ImageVector
        get() {
            if (_rawPlaylistAddV3 != null) return _rawPlaylistAddV3!!
            val builder = ImageVector.Builder(
                name = "RawPlaylistAddV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5.1f, 7.8f)
                horizontalLineToRelative(8.5f)
                moveTo(5.1f, 12f)
                horizontalLineToRelative(7f)
                moveTo(5.1f, 16.2f)
                horizontalLineToRelative(5.2f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(16.7f, 13.6f)
                verticalLineToRelative(6.2f)
                moveTo(13.6f, 16.7f)
                horizontalLineToRelative(6.2f)
            }
            return builder.build().also { _rawPlaylistAddV3 = it }
        }
    private var _rawPlaylistAddV3: ImageVector? = null

    val RawPlaylistV3: ImageVector
        get() {
            if (_rawPlaylistV3 != null) return _rawPlaylistV3!!
            val builder = ImageVector.Builder(
                name = "RawPlaylistV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.18f,
                stroke = null,
                strokeAlpha = 0.18f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.1f, 4.7f)
                horizontalLineTo(14.9f)
                quadTo(17.9f, 4.7f, 17.9f, 7.7f)
                verticalLineTo(16.3f)
                quadTo(17.9f, 19.3f, 14.9f, 19.3f)
                horizontalLineTo(8.1f)
                quadTo(5.1f, 19.3f, 5.1f, 16.3f)
                verticalLineTo(7.7f)
                quadTo(5.1f, 4.7f, 8.1f, 4.7f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.38f,
                stroke = null,
                strokeAlpha = 0.38f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.2f, 6.5f)
                horizontalLineTo(16f)
                quadTo(19f, 6.5f, 19f, 9.5f)
                verticalLineTo(16.3f)
                quadTo(19f, 19.3f, 16f, 19.3f)
                horizontalLineTo(10.2f)
                quadTo(7.2f, 19.3f, 7.2f, 16.3f)
                verticalLineTo(9.5f)
                quadTo(7.2f, 6.5f, 10.2f, 6.5f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.4f, 10f)
                horizontalLineToRelative(5.4f)
                moveTo(10.4f, 13f)
                horizontalLineToRelative(4.3f)
                moveTo(10.4f, 16f)
                horizontalLineToRelative(2.8f)
            }
            return builder.build().also { _rawPlaylistV3 = it }
        }
    private var _rawPlaylistV3: ImageVector? = null

    val RawPreviousV1: ImageVector
        get() {
            if (_rawPreviousV1 != null) return _rawPreviousV1!!
            val builder = ImageVector.Builder(
                name = "RawPreviousV1",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5.9f, 7f)
                horizontalLineTo(6.2f)
                quadTo(7.3f, 7f, 7.3f, 8.1f)
                verticalLineTo(15.9f)
                quadTo(7.3f, 17f, 6.2f, 17f)
                horizontalLineTo(5.9f)
                quadTo(4.8f, 17f, 4.8f, 15.9f)
                verticalLineTo(8.1f)
                quadTo(4.8f, 7f, 5.9f, 7f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(18f, 7.9f)
                curveToRelative(0f, -0.8f, -0.9f, -1.3f, -1.6f, -0.9f)
                lineToRelative(-6.7f, 4.1f)
                curveToRelative(-0.7f, 0.4f, -0.7f, 1.4f, 0f, 1.8f)
                lineToRelative(6.7f, 4.1f)
                curveToRelative(0.7f, 0.4f, 1.6f, -0.1f, 1.6f, -0.9f)
                verticalLineTo(7.9f)
                close()
            }
            return builder.build().also { _rawPreviousV1 = it }
        }
    private var _rawPreviousV1: ImageVector? = null

    val RawQualityV3: ImageVector
        get() {
            if (_rawQualityV3 != null) return _rawQualityV3!!
            val builder = ImageVector.Builder(
                name = "RawQualityV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.7f, 5.2f)
                horizontalLineTo(16.3f)
                quadTo(19.5f, 5.2f, 19.5f, 8.4f)
                verticalLineTo(15.6f)
                quadTo(19.5f, 18.8f, 16.3f, 18.8f)
                horizontalLineTo(7.7f)
                quadTo(4.5f, 18.8f, 4.5f, 15.6f)
                verticalLineTo(8.4f)
                quadTo(4.5f, 5.2f, 7.7f, 5.2f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8f, 15.6f)
                verticalLineTo(9f)
                moveTo(12f, 15.6f)
                verticalLineTo(6.9f)
                moveTo(16f, 15.6f)
                verticalLineToRelative(-4.4f)
            }
            builder.path(
                fill = null,
                fillAlpha = 0.45f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 0.45f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.2f, 18.6f)
                horizontalLineToRelative(9.6f)
            }
            return builder.build().also { _rawQualityV3 = it }
        }
    private var _rawQualityV3: ImageVector? = null

    val RawQueueV3: ImageVector
        get() {
            if (_rawQueueV3 != null) return _rawQueueV3!!
            val builder = ImageVector.Builder(
                name = "RawQueueV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5.2f, 7.1f)
                horizontalLineToRelative(9.8f)
                moveTo(5.2f, 12f)
                horizontalLineToRelative(12.5f)
                moveTo(5.2f, 16.9f)
                horizontalLineToRelative(7.2f)
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.2f, 14.9f)
                curveTo(18.3046f, 14.9f, 19.2f, 15.7954f, 19.2f, 16.9f)
                curveTo(19.2f, 18.0046f, 18.3046f, 18.9f, 17.2f, 18.9f)
                curveTo(16.0954f, 18.9f, 15.2f, 18.0046f, 15.2f, 16.9f)
                curveTo(15.2f, 15.7954f, 16.0954f, 14.9f, 17.2f, 14.9f)
                close()
            }
            return builder.build().also { _rawQueueV3 = it }
        }
    private var _rawQueueV3: ImageVector? = null

    val RawRefreshV2: ImageVector
        get() {
            if (_rawRefreshV2 != null) return _rawRefreshV2!!
            val builder = ImageVector.Builder(
                name = "RawRefreshV2",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 3.7f)
                curveTo(16.584f, 3.7f, 20.3f, 7.416f, 20.3f, 12f)
                curveTo(20.3f, 16.584f, 16.584f, 20.3f, 12f, 20.3f)
                curveTo(7.416f, 20.3f, 3.7f, 16.584f, 3.7f, 12f)
                curveTo(3.7f, 7.416f, 7.416f, 3.7f, 12f, 3.7f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.8f, 8.5f)
                curveToRelative(-1.1f, -1.6f, -3f, -2.7f, -5.2f, -2.7f)
                curveToRelative(-3.4f, 0f, -6.2f, 2.8f, -6.2f, 6.2f)
                reflectiveCurveToRelative(2.8f, 6.2f, 6.2f, 6.2f)
                curveToRelative(2.6f, 0f, 4.8f, -1.6f, 5.8f, -3.9f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17.8f, 5.3f)
                verticalLineToRelative(3.2f)
                horizontalLineToRelative(-3.2f)
            }
            return builder.build().also { _rawRefreshV2 = it }
        }
    private var _rawRefreshV2: ImageVector? = null

    val RawRepeatV3: ImageVector
        get() {
            if (_rawRepeatV3 != null) return _rawRepeatV3!!
            val builder = ImageVector.Builder(
                name = "RawRepeatV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.2f, 7.3f)
                horizontalLineToRelative(7.4f)
                curveToRelative(2.3f, 0f, 4.1f, 1.8f, 4.1f, 4.1f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveToRelative(15.9f, 4.9f)
                lineToRelative(2.8f, 2.4f)
                lineToRelative(-2.8f, 2.4f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(16.8f, 16.7f)
                horizontalLineTo(9.4f)
                curveToRelative(-2.3f, 0f, -4.1f, -1.8f, -4.1f, -4.1f)
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveToRelative(8.1f, 19.1f)
                lineToRelative(-2.8f, -2.4f)
                lineToRelative(2.8f, -2.4f)
            }
            return builder.build().also { _rawRepeatV3 = it }
        }
    private var _rawRepeatV3: ImageVector? = null

    val RawSearchV3: ImageVector
        get() {
            if (_rawSearchV3 != null) return _rawSearchV3!!
            val builder = ImageVector.Builder(
                name = "RawSearchV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10.6f, 5.7f)
                curveTo(13.1957f, 5.7f, 15.3f, 7.8043f, 15.3f, 10.4f)
                curveTo(15.3f, 12.9957f, 13.1957f, 15.1f, 10.6f, 15.1f)
                curveTo(8.0043f, 15.1f, 5.9f, 12.9957f, 5.9f, 10.4f)
                curveTo(5.9f, 7.8043f, 8.0043f, 5.7f, 10.6f, 5.7f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveToRelative(14.1f, 14f)
                lineToRelative(4.2f, 4.2f)
            }
            return builder.build().also { _rawSearchV3 = it }
        }
    private var _rawSearchV3: ImageVector? = null

    val RawSettingsV3: ImageVector
        get() {
            if (_rawSettingsV3 != null) return _rawSettingsV3!!
            val builder = ImageVector.Builder(
                name = "RawSettingsV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7.4f, 4.4f)
                horizontalLineTo(16.6f)
                quadTo(20f, 4.4f, 20f, 7.8f)
                verticalLineTo(16.2f)
                quadTo(20f, 19.6f, 16.6f, 19.6f)
                horizontalLineTo(7.4f)
                quadTo(4f, 19.6f, 4f, 16.2f)
                verticalLineTo(7.8f)
                quadTo(4f, 4.4f, 7.4f, 4.4f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 0.55f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 0.55f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8f, 9f)
                horizontalLineToRelative(8f)
                moveTo(8f, 15f)
                horizontalLineToRelative(8f)
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(10f, 6.9f)
                curveTo(11.1598f, 6.9f, 12.1f, 7.8402f, 12.1f, 9f)
                curveTo(12.1f, 10.1598f, 11.1598f, 11.1f, 10f, 11.1f)
                curveTo(8.8402f, 11.1f, 7.9f, 10.1598f, 7.9f, 9f)
                curveTo(7.9f, 7.8402f, 8.8402f, 6.9f, 10f, 6.9f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(14f, 12.9f)
                curveTo(15.1598f, 12.9f, 16.1f, 13.8402f, 16.1f, 15f)
                curveTo(16.1f, 16.1598f, 15.1598f, 17.1f, 14f, 17.1f)
                curveTo(12.8402f, 17.1f, 11.9f, 16.1598f, 11.9f, 15f)
                curveTo(11.9f, 13.8402f, 12.8402f, 12.9f, 14f, 12.9f)
                close()
            }
            return builder.build().also { _rawSettingsV3 = it }
        }
    private var _rawSettingsV3: ImageVector? = null

    val RawShareV3: ImageVector
        get() {
            if (_rawShareV3 != null) return _rawShareV3!!
            val builder = ImageVector.Builder(
                name = "RawShareV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7f, 9.9f)
                curveTo(8.1598f, 9.9f, 9.1f, 10.8402f, 9.1f, 12f)
                curveTo(9.1f, 13.1598f, 8.1598f, 14.1f, 7f, 14.1f)
                curveTo(5.8402f, 14.1f, 4.9f, 13.1598f, 4.9f, 12f)
                curveTo(4.9f, 10.8402f, 5.8402f, 9.9f, 7f, 9.9f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(16.8f, 5.1f)
                curveTo(17.9598f, 5.1f, 18.9f, 6.0402f, 18.9f, 7.2f)
                curveTo(18.9f, 8.3598f, 17.9598f, 9.3f, 16.8f, 9.3f)
                curveTo(15.6402f, 9.3f, 14.7f, 8.3598f, 14.7f, 7.2f)
                curveTo(14.7f, 6.0402f, 15.6402f, 5.1f, 16.8f, 5.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(16.8f, 14.7f)
                curveTo(17.9598f, 14.7f, 18.9f, 15.6402f, 18.9f, 16.8f)
                curveTo(18.9f, 17.9598f, 17.9598f, 18.9f, 16.8f, 18.9f)
                curveTo(15.6402f, 18.9f, 14.7f, 17.9598f, 14.7f, 16.8f)
                curveTo(14.7f, 15.6402f, 15.6402f, 14.7f, 16.8f, 14.7f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveToRelative(8.9f, 11f)
                lineToRelative(6f, -2.9f)
                moveTo(8.9f, 13f)
                lineToRelative(6f, 2.9f)
            }
            return builder.build().also { _rawShareV3 = it }
        }
    private var _rawShareV3: ImageVector? = null

    val RawStarV2: ImageVector
        get() {
            if (_rawStarV2 != null) return _rawStarV2!!
            val builder = ImageVector.Builder(
                name = "RawStarV2",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 3.7f)
                curveTo(16.584f, 3.7f, 20.3f, 7.416f, 20.3f, 12f)
                curveTo(20.3f, 16.584f, 16.584f, 20.3f, 12f, 20.3f)
                curveTo(7.416f, 20.3f, 3.7f, 16.584f, 3.7f, 12f)
                curveTo(3.7f, 7.416f, 7.416f, 3.7f, 12f, 3.7f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveToRelative(12f, 4.8f)
                lineToRelative(2f, 4.2f)
                lineToRelative(4.6f, 0.6f)
                lineToRelative(-3.3f, 3.2f)
                lineToRelative(0.8f, 4.5f)
                lineToRelative(-4.1f, -2.2f)
                lineToRelative(-4.1f, 2.2f)
                lineToRelative(0.8f, -4.5f)
                lineToRelative(-3.3f, -3.2f)
                lineToRelative(4.6f, -0.6f)
                lineToRelative(2f, -4.2f)
                close()
            }
            return builder.build().also { _rawStarV2 = it }
        }
    private var _rawStarV2: ImageVector? = null

    val RawThumbDownV3: ImageVector
        get() {
            if (_rawThumbDownV3 != null) return _rawThumbDownV3!!
            val builder = ImageVector.Builder(
                name = "RawThumbDownV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.72f,
                stroke = null,
                strokeAlpha = 0.72f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(14.5f, 13.7f)
                verticalLineToRelative(3.2f)
                curveToRelative(0f, 1.3f, -1f, 2.4f, -2.3f, 2.4f)
                curveToRelative(-0.5f, 0f, -0.9f, -0.4f, -0.9f, -0.9f)
                verticalLineToRelative(-4f)
                horizontalLineTo(7.1f)
                curveToRelative(-1f, 0f, -1.7f, -0.9f, -1.5f, -1.9f)
                lineToRelative(1.1f, -5.5f)
                curveToRelative(0.2f, -1f, 1f, -1.7f, 2f, -1.7f)
                horizontalLineToRelative(5.8f)
                verticalLineToRelative(8.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.32f,
                stroke = null,
                strokeAlpha = 0.32f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(17.1f, 5.3f)
                horizontalLineTo(17.8f)
                quadTo(19f, 5.3f, 19f, 6.5f)
                verticalLineTo(12.5f)
                quadTo(19f, 13.7f, 17.8f, 13.7f)
                horizontalLineTo(17.1f)
                quadTo(15.9f, 13.7f, 15.9f, 12.5f)
                verticalLineTo(6.5f)
                quadTo(15.9f, 5.3f, 17.1f, 5.3f)
                close()
            }
            return builder.build().also { _rawThumbDownV3 = it }
        }
    private var _rawThumbDownV3: ImageVector? = null

    val RawThumbUpV3: ImageVector
        get() {
            if (_rawThumbUpV3 != null) return _rawThumbUpV3!!
            val builder = ImageVector.Builder(
                name = "RawThumbUpV3",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(8.3f, 4.1f)
                horizontalLineTo(15.7f)
                quadTo(19.9f, 4.1f, 19.9f, 8.3f)
                verticalLineTo(15.7f)
                quadTo(19.9f, 19.9f, 15.7f, 19.9f)
                horizontalLineTo(8.3f)
                quadTo(4.1f, 19.9f, 4.1f, 15.7f)
                verticalLineTo(8.3f)
                quadTo(4.1f, 4.1f, 8.3f, 4.1f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.72f,
                stroke = null,
                strokeAlpha = 0.72f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(9.5f, 10.3f)
                verticalLineTo(7.1f)
                curveToRelative(0f, -1.3f, 1f, -2.4f, 2.3f, -2.4f)
                curveToRelative(0.5f, 0f, 0.9f, 0.4f, 0.9f, 0.9f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(4.2f)
                curveToRelative(1f, 0f, 1.7f, 0.9f, 1.5f, 1.9f)
                lineToRelative(-1.1f, 5.5f)
                curveToRelative(-0.2f, 1f, -1f, 1.7f, -2f, 1.7f)
                horizontalLineTo(9.5f)
                verticalLineToRelative(-8.4f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.32f,
                stroke = null,
                strokeAlpha = 0.32f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(6.2f, 10.3f)
                horizontalLineTo(6.9f)
                quadTo(8.1f, 10.3f, 8.1f, 11.5f)
                verticalLineTo(17.5f)
                quadTo(8.1f, 18.7f, 6.9f, 18.7f)
                horizontalLineTo(6.2f)
                quadTo(5f, 18.7f, 5f, 17.5f)
                verticalLineTo(11.5f)
                quadTo(5f, 10.3f, 6.2f, 10.3f)
                close()
            }
            return builder.build().also { _rawThumbUpV3 = it }
        }
    private var _rawThumbUpV3: ImageVector? = null

    val RawTrackRadioV2: ImageVector
        get() {
            if (_rawTrackRadioV2 != null) return _rawTrackRadioV2!!
            val builder = ImageVector.Builder(
                name = "RawTrackRadioV2",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            )
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.14f,
                stroke = null,
                strokeAlpha = 0.14f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 3.7f)
                curveTo(16.584f, 3.7f, 20.3f, 7.416f, 20.3f, 12f)
                curveTo(20.3f, 16.584f, 16.584f, 20.3f, 12f, 20.3f)
                curveTo(7.416f, 20.3f, 3.7f, 16.584f, 3.7f, 12f)
                curveTo(3.7f, 7.416f, 7.416f, 3.7f, 12f, 3.7f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 0.16f,
                stroke = null,
                strokeAlpha = 0.16f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.6f)
                curveTo(16.0869f, 4.6f, 19.4f, 7.9131f, 19.4f, 12f)
                curveTo(19.4f, 16.0869f, 16.0869f, 19.4f, 12f, 19.4f)
                curveTo(7.9131f, 19.4f, 4.6f, 16.0869f, 4.6f, 12f)
                curveTo(4.6f, 7.9131f, 7.9131f, 4.6f, 12f, 4.6f)
                close()
            }
            builder.path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 0.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 9.2f)
                curveTo(13.5464f, 9.2f, 14.8f, 10.4536f, 14.8f, 12f)
                curveTo(14.8f, 13.5464f, 13.5464f, 14.8f, 12f, 14.8f)
                curveTo(10.4536f, 14.8f, 9.2f, 13.5464f, 9.2f, 12f)
                curveTo(9.2f, 10.4536f, 10.4536f, 9.2f, 12f, 9.2f)
                close()
            }
            builder.path(
                fill = null,
                fillAlpha = 1.0f,
                stroke = SolidColor(Color.Black),
                strokeAlpha = 1.0f,
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4.6f)
                curveToRelative(2.7f, 0f, 4.9f, 1.3f, 6.4f, 3.3f)
                moveTo(12f, 19.4f)
                curveToRelative(-2.7f, 0f, -4.9f, -1.3f, -6.4f, -3.3f)
            }
            return builder.build().also { _rawTrackRadioV2 = it }
        }
    private var _rawTrackRadioV2: ImageVector? = null

}
