package com.liquidmusicglass.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Единая шкала размеров, скруглений и типографики.
 *
 * Смысл файла — чтобы стиль переносился на остальные экраны заменой значений
 * здесь, а не правкой чисел по месту. Пока величины разбросаны по экранам, любая
 * смена ритма превращается в обход всех файлов, и один-два всегда забываются.
 */
object LiquidMetrics {

    // ── Скругления ───────────────────────────────────────────────────────────
    /** Лист контента, наезжающий на шапку. */
    val SheetRadius = 32.dp

    /** Крупные карточки: релизы, блоки-подборки. */
    val CardRadius = 24.dp

    /** Обложки внутри карточек и строк списка. */
    val CoverRadius = 16.dp

    /** Мелкие обложки в строках треков. */
    val CoverRadiusSmall = 13.dp

    /** Кнопки-капсулы и круглые иконки. */
    val Pill = RoundedCornerShape(50)

    val SheetShape = RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius)
    val CardShape = RoundedCornerShape(CardRadius)
    val CoverShape = RoundedCornerShape(CoverRadius)
    val CoverShapeSmall = RoundedCornerShape(CoverRadiusSmall)

    // ── Отступы ──────────────────────────────────────────────────────────────
    /** Поля экрана слева и справа. */
    val ScreenPadding = 24.dp

    /** Расстояние между разделами. */
    val SectionGap = 34.dp

    /** Внутренние поля карточек. */
    val CardPadding = 12.dp

    /** Насколько лист контента наезжает на шапку. */
    val SheetOverlap = 32.dp

    /** Высота шапки экрана артиста. */
    val HeaderHeight = 528.dp

    // ── Управляющие элементы ─────────────────────────────────────────────────
    /** Главные кнопки действий («Слушать», «Вперемешку»). */
    val ActionButtonHeight = 52.dp

    /** Круглые кнопки поверх шапки. */
    val GlassButtonSize = 36.dp

    /** Обложка в карточке свежего релиза. */
    val ReleaseCoverSize = 72.dp

    /** Обложка в строке трека. */
    val TrackCoverSize = 52.dp

    /** Отступ разделителя слева — под текстом, а не под обложкой. */
    val DividerInset = 92.dp

    // ── Возвышение ───────────────────────────────────────────────────────────
    /** Карточки, приподнятые над листом (свежий релиз и подобные). */
    val CardElevation = 10.dp

    /** Главные кнопки действий: лёгкий отрыв, а не выпуклость. */
    val ButtonElevation = 6.dp

    /**
     * Обложки в списках и каруселях. Меньше, чем у карточек: обложек на экране
     * много, и одинаково сильная тень у каждой превращает список в рябь.
     */
    val CoverElevation = 6.dp

    // ── Типографика ──────────────────────────────────────────────────────────
    /** Имя артиста в шапке. Плотный трекинг — иначе крупный текст выглядит рыхлым. */
    val TitleHuge = 42.sp
    val TitleHugeSpacing = (-1.8).sp
    val TitleHugeWeight = FontWeight.ExtraBold

    /** Заголовки разделов. */
    val SectionTitle = 20.sp
    val SectionTitleSpacing = (-0.6).sp
    val SectionTitleWeight = FontWeight.Bold

    /** Название трека в строке списка. */
    val RowTitle = 16.sp

    /** Название в карточке. */
    val CardTitle = 15.5.sp

    /** Подписи под названием. */
    val Caption = 13.sp

    /** Подпись в шапке (жанр). */
    val HeaderCaption = 13.5.sp

    /** Текст на кнопках действий. */
    val ActionLabel = 16.sp

    /** Ссылка «Показать все». */
    val LinkLabel = 14.sp

    // ── Очередь ──────────────────────────────────────────────────────────────
    /** Высота строки. По ней же считается шаг перестановки при перетаскивании. */
    val QueueRowHeight = 56.dp

    /** Обложка в строке очереди. */
    val QueueRowCover = 44.dp

    /** Обложка играющего трека в закреплённой шапке. */
    val QueueNowPlayingCover = 60.dp

    /** Ручка перестановки и её значок. */
    val QueueHandleSize = 36.dp
    val QueueHandleIcon = 20.dp

    /** Поля строки и шапки по горизонтали. */
    val QueuePadding = 20.dp

    /** Насколько надо утянуть строку вбок, чтобы удалить. */
    val QueueSwipeThreshold = 96.dp

    /** Куда строка улетает при удалении (в пикселях, за край экрана). */
    const val QueueSwipeFlyOut = 1400f

    /** Скорость, при которой свайп срабатывает, не дотянув до порога. */
    const val QueueSwipeVelocity = 3000f

    /**
     * Масштаб поднятой строки. У Apple 0.85 за 300 мс — сжатие настолько
     * характерное, что читается как их приложение; берём едва заметное.
     */
    const val QueueDragLiftScale = 0.97f

    /** Тень поднятой строки. */
    const val QueueDragElevation = 12f
}

/**
 * Цвета для нового оформления, отдельно для светлой и тёмной темы.
 *
 * Шапка на экране артиста всегда тёмная — под ней стоит фотография, и светлый
 * текст поверх неё читается в обеих темах. Меняется только лист контента.
 */
object LiquidSurfaces {

    /** Фон листа контента под шапкой. */
    fun sheet(isDark: Boolean): Color =
        if (isDark) Color(0xFF0E0E10) else Color.White

    /** Заливка карточек внутри листа. */
    fun card(isDark: Boolean): Color =
        if (isDark) Color(0xFF1B1B1E) else Color(0xFFF5F5F7)

    /** Заливка карточки при нажатии. */
    fun cardPressed(isDark: Boolean): Color =
        if (isDark) Color(0xFF232327) else Color(0xFFEFEFF1)

    /** Основной текст на листе. */
    fun textPrimary(isDark: Boolean): Color =
        if (isDark) Color(0xFFF2F2F5) else Color(0xFF111111)

    /** Второстепенный текст: подписи, годы, счётчики. */
    fun textSecondary(isDark: Boolean): Color =
        if (isDark) Color(0xFF9A9AA0) else Color(0xFF8E8E93)

    /** Совсем тихий текст: номера строк. */
    fun textTertiary(isDark: Boolean): Color =
        if (isDark) Color(0xFF6E6E76) else Color(0xFFAEAEB2)

    /** Разделитель между строками. */
    fun divider(isDark: Boolean): Color =
        if (isDark) Color(0xFF232326) else Color(0xFFF2F2F2)

    /** Круглая кнопка на листе (не поверх шапки). */
    fun buttonSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF232327) else Color.White

    /**
     * Подсветка тени. В светлой теме обычная чёрная, в тёмной — чуть светлее:
     * чёрная тень на почти чёрном фоне не видна, и карточка выглядит плоской.
     */
    fun shadowTint(isDark: Boolean): Color =
        if (isDark) Color(0xFF6A6A75) else Color.Black

    /** Полоска-ручка сверху листа. */
    fun grabber(isDark: Boolean): Color =
        if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)

    // ── Поверх шапки: всегда светлые элементы на тёмном фото ────────────────
    /** Заливка стеклянных кнопок. */
    val glassFill = Color.White.copy(alpha = 0.14f)

    /** Контур стеклянных кнопок. */
    val glassBorder = Color.White.copy(alpha = 0.15f)

    /** Вторая кнопка действия поверх шапки. */
    val glassAction = Color.White.copy(alpha = 0.14f)

    val onHeaderPrimary = Color.White
    val onHeaderSecondary = Color.White.copy(alpha = 0.70f)

    // ── Очередь ─────────────────────────────────────────────────────────────
    /**
     * Затемнение под очередью. Очередь лежит на фоне из обложки, поэтому текст
     * здесь светлый в обеих темах — как на шапке экрана артиста. Тема меняет не
     * цвет текста, а плотность затемнения: в светлой фон обложки должен остаться
     * различимым, иначе обе темы выглядят одинаково.
     *
     * В split-режиме градиент горизонтальный, с растушёванной левой кромкой —
     * вертикальный давал бы видимый шов по границе половин.
     */
    fun queueScrim(isDark: Boolean, splitMode: Boolean): Brush {
        val top = if (isDark) 0.18f else 0.10f
        val mid = if (isDark) 0.30f else 0.22f
        val bottom = if (isDark) 0.48f else 0.38f
        return if (splitMode) {
            Brush.horizontalGradient(
                0.00f to Color.Transparent,
                0.14f to Color.Black.copy(alpha = mid),
                1.00f to Color.Black.copy(alpha = bottom)
            )
        } else {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Black.copy(alpha = top),
                    0.45f to Color.Black.copy(alpha = mid),
                    1.00f to Color.Black.copy(alpha = bottom)
                )
            )
        }
    }

    /** Подложка строки, поднятой для перестановки. */
    val queueRowDragged = Color.White.copy(alpha = 0.06f)

    /** Подложка, проступающая под строкой при свайпе-удалении. */
    fun queueDestructive(accent: Color): Color = accent.copy(alpha = 0.85f)
}
