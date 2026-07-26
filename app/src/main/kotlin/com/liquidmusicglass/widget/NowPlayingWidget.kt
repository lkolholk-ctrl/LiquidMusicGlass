package com.liquidmusicglass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.PlayerController

/**
 * Виджет «Сейчас играет» на домашнем экране.
 *
 * Обложку намеренно не рисуем: у стриминговых треков она приходит по сети, а
 * виджет обновляется в чужом процессе (лаунчере) — грузить туда картинки значит
 * либо тянуть их синхронно, либо мигать пустым местом. Название, исполнитель и
 * управление дают то, ради чего виджет ставят, и работают мгновенно.
 *
 * Состояние читается из [PlayerController] напрямую: это объект уровня процесса,
 * и к моменту отрисовки виджета он уже живёт вместе с сервисом воспроизведения.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetBody()
            }
        }
    }

    @Composable
    private fun WidgetBody() {
        val track = PlayerController.currentTrack.value
        val isPlaying = PlayerController.isPlaying.value

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = track?.title ?: "Nothing playing",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            Text(
                text = track?.artist ?: "Tap play to resume",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp
                ),
                maxLines = 1
            )

            Spacer(modifier = GlanceModifier.padding(top = 8.dp))

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                ControlButton(label = "◀◀", action = WidgetAction.PREVIOUS)
                Spacer(modifier = GlanceModifier.width(12.dp))
                ControlButton(
                    label = if (isPlaying) "❚❚" else "▶",
                    action = WidgetAction.PLAY_PAUSE
                )
                Spacer(modifier = GlanceModifier.width(12.dp))
                ControlButton(label = "▶▶", action = WidgetAction.NEXT)
            }
        }
    }

    @Composable
    private fun ControlButton(label: String, action: WidgetAction) {
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 18.sp
            ),
            modifier = GlanceModifier.clickable(
                actionRunCallback<WidgetControlCallback>(
                    actionParametersOf(WidgetControlCallback.actionKey to action.name)
                )
            )
        )
    }
}

/** Кнопки виджета. */
enum class WidgetAction { PLAY_PAUSE, NEXT, PREVIOUS }

/** Обрабатывает нажатия: команды идут в тот же контроллер, что и кнопки в приложении. */
class WidgetControlCallback : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        when (parameters[actionKey]) {
            WidgetAction.PLAY_PAUSE.name -> PlayerController.togglePlayPause(context)
            WidgetAction.NEXT.name -> PlayerController.skipNext(context)
            WidgetAction.PREVIOUS.name -> PlayerController.skipPrevious(context)
        }
        NowPlayingWidget().updateAll(context)
    }

    companion object {
        val actionKey = ActionParameters.Key<String>("widget_action")
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
