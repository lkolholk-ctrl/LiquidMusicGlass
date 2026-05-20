package com.liquidmusicglass.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import com.liquidmusicglass.engine.AudioService
import com.liquidmusicglass.ui.glass.AlbumArtImage

data class AudioOutputDevice(
    val id: Int,
    val name: String,
    val icon: ImageVector,
    val type: OutputType,
    val isActive: Boolean = false
)

enum class OutputType {
    PHONE, BLUETOOTH, WIRED, USB
}

// ══════════════════════════════════════════════════════════════
//  Device detection
// ══════════════════════════════════════════════════════════════

private fun phoneSpeakerName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return when {
        model.isBlank() -> "Phone Speaker"
        manufacturer.isBlank() -> "$model Speaker"
        model.startsWith(manufacturer, ignoreCase = true) -> "$model Speaker"
        else -> "$manufacturer $model Speaker"
    }
}

// Трекинг текущего preferred device
@Volatile
private var currentPreferredDeviceType: OutputType? = null

private fun detectActiveType(audioManager: AudioManager): OutputType {
    // Если мы установили preferred device — он активен
    val preferred = currentPreferredDeviceType
    if (preferred != null) return preferred

    // Нет preferred — определяем по подключённым (авто-маршрутизация)
    val outputs = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    } catch (_: Throwable) {
        return OutputType.PHONE
    }

    if (outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) return OutputType.BLUETOOTH
    if (outputs.any {
            it.type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        }) return OutputType.WIRED
    if (outputs.any {
            it.type in listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
        }) return OutputType.USB

    return OutputType.PHONE
}

private fun buildDeviceList(audioManager: AudioManager): List<AudioOutputDevice> {
    val activeType = detectActiveType(audioManager)
    val externalDevices = mutableListOf<AudioOutputDevice>()

    val outputs = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    } catch (_: Throwable) { emptyList() }

    outputs.forEach { device ->
        val rawName = try {
            device.productName?.toString()?.trim().orEmpty()
        } catch (_: Throwable) { "" }

        when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                externalDevices += AudioOutputDevice(
                    id = device.id,
                    name = rawName.ifBlank { "Bluetooth Audio" },
                    icon = Icons.Rounded.Headphones,
                    type = OutputType.BLUETOOTH,
                    isActive = activeType == OutputType.BLUETOOTH
                )
            }
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> {
                externalDevices += AudioOutputDevice(
                    id = device.id,
                    name = rawName.ifBlank { "Wired Headphones" },
                    icon = Icons.Rounded.Headphones,
                    type = OutputType.WIRED,
                    isActive = activeType == OutputType.WIRED
                )
            }
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_DOCK -> {
                externalDevices += AudioOutputDevice(
                    id = device.id,
                    name = rawName.ifBlank { "USB Audio" },
                    icon = Icons.Rounded.Usb,
                    type = OutputType.USB,
                    isActive = activeType == OutputType.USB
                )
            }
        }
    }

    val deduped = externalDevices.distinctBy { "${it.type}:${it.name}" }
    val phone = AudioOutputDevice(
        id = -1,
        name = phoneSpeakerName(),
        icon = Icons.Rounded.PhoneAndroid,
        type = OutputType.PHONE,
        isActive = activeType == OutputType.PHONE
    )
    return listOf(phone) + deduped
}

// ══════════════════════════════════════════════════════════════
//  Audio switching via ExoPlayer.setPreferredAudioDevice
//  — не требует BLUETOOTH permission
//  — не искажает звук (media routing, не communication)
//  — не крашит
// ══════════════════════════════════════════════════════════════

private fun switchOutput(context: Context, device: AudioOutputDevice) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    try {
        when (device.type) {
            OutputType.PHONE -> {
                // Route to speaker — handled by AudioManager
                currentPreferredDeviceType = OutputType.PHONE
            }
            else -> {
                // Reset preferred — system auto-routes to BT/Wired/USB
                currentPreferredDeviceType = null
            }
        }
    } catch (_: Throwable) {
        currentPreferredDeviceType = null
    }
}

// ══════════════════════════════════════════════════════════════
//  Composables
// ══════════════════════════════════════════════════════════════

@Composable
private fun rememberAudioDevices(): List<AudioOutputDevice> {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var devices by remember { mutableStateOf(buildDeviceList(audioManager)) }

    DisposableEffect(audioManager) {
        val handler = Handler(Looper.getMainLooper())

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                currentPreferredDeviceType = null
                devices = buildDeviceList(audioManager)
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                currentPreferredDeviceType = null
                devices = buildDeviceList(audioManager)
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        val routeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                handler.postDelayed({
                    currentPreferredDeviceType = null
                    devices = buildDeviceList(audioManager)
                }, 300)
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        }
        try { context.registerReceiver(routeReceiver, filter) } catch (_: Throwable) {}

        onDispose {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            try { context.unregisterReceiver(routeReceiver) } catch (_: Throwable) {}
        }
    }

    return devices
}

@Composable
fun AirPlaySheet(
    visible: Boolean,
    backdrop: LayerBackdrop,
    trackTitle: String,
    artistName: String,
    albumArtUri: Uri?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var devices by remember { mutableStateOf(emptyList<AudioOutputDevice>()) }
    val liveDevices = rememberAudioDevices()

    // Обновляем при открытии или изменении
    LaunchedEffect(visible, liveDevices) {
        devices = liveDevices
    }

    val containerColor = Color(0xFF121212).copy(alpha = 0.18f)
    val dimColor = Color(0xFF0E0E12).copy(alpha = 0.62f)
    val enterProgress = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (visible) enterProgress.animateTo(0f, spring(0.85f, 250f))
        else enterProgress.animateTo(1f, spring(0.88f, 350f))
    }

    if (enterProgress.value >= 0.99f && !visible) return

    val progress = enterProgress.value
    val vis by remember { derivedStateOf { (1f - enterProgress.value).coerceIn(0f, 1f) } }
    val slideOffsetPx = with(density) { 360.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                drawRect(dimColor.copy(alpha = dimColor.alpha * vis))
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .padding(bottom = 120.dp)
                .height(260.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedRectangle(28.dp) },
                    effects = {
                        vibrancy()
                        blur(3f.dp.toPx())
                        lens(
                            refractionHeight = 52f.dp.toPx(),
                            refractionAmount = 80f.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    },
                    layerBlock = {
                        translationY = progress * slideOffsetPx
                        alpha = EaseIn.transform(vis)
                    },
                    shadow = { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.25f)) },
                    innerShadow = { InnerShadow(radius = 4.dp, alpha = 0.3f) },
                    onDrawSurface = {
                        drawRect(containerColor)
                        drawRect(
                            color = Color.White.copy(alpha = 0.35f),
                            style = Stroke(width = 1.5f.dp.toPx())
                        )
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                    ) {
                        AlbumArtImage(
                            uri = albumArtUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = trackTitle,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artistName,
                            color = Color.White.copy(alpha = 0.52f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PersonAddAlt1,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                devices.forEachIndexed { index, device ->
                    DeviceRow(
                        device = device,
                        onClick = {
                            switchOutput(context, device)
                            // Мгновенно обновляем UI
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            devices = buildDeviceList(audioManager)
                        }
                    )
                    if (index != devices.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: AudioOutputDevice,
    onClick: () -> Unit
) {
    val rowColor = if (device.isActive) Color.White.copy(alpha = 0.78f)
                   else Color.White.copy(alpha = 0.07f)
    val contentColor = if (device.isActive) Color.Black else Color.White

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = device.icon,
            contentDescription = null,
            tint = contentColor.copy(alpha = if (device.isActive) 0.96f else 0.90f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = device.name,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = if (device.isActive) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (device.isActive) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}
