package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Earbuds
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Where the music is coming out, and how loud.
 *
 * Replaces two things that did not work. On Android 14 and up the headphones
 * glyph opened the *system* output switcher — a panel this app does not control,
 * styled like nothing else here, and one that routes the whole phone rather than
 * this player. Below that it opened a stock [android.app.AlertDialog] driven by
 * [android.media.MediaRouter], whose `selectRoute` is a request the framework is
 * free to ignore, and usually did.
 *
 * This one switches by telling our own player which sink to render into, which
 * is the only routing decision the app owns and the one that actually takes
 * effect — see [AudioRouting].
 *
 * A [PlayerDrawer] off the bottom edge, as the rest of the app's sheets are.
 * Outputs come first and volume follows. Every available device stays visible:
 * on a phone there are usually two, so a disclosure would cost a tap merely to
 * reveal a single row.
 */
@Composable
internal fun AudioOutputSheet(
    hazeState: HazeState,
    accountName: String?,
    onDismiss: () -> Unit,
    onOpenPipeline: () -> Unit,
    onOpenCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outputs = rememberAudioOutputs()
    val cast by PlayerPlatform.host.castState.collectAsStateWithLifecycle()
    val watch by PlayerPlatform.host.watchOutput.collectAsStateWithLifecycle()
    // A watch comes and goes; ask again each time the drawer opens.
    LaunchedEffect(Unit) { PlayerPlatform.host.refreshWatchOutput() }

    // True while the sound is coming out of something that is not this phone's
    // own audio stack. The in-app level is a DSP gain inside our sink, so it
    // cannot reach either of those outputs.
    val remoteOutput = cast.connectedName != null || watch.active

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.audio_output),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        // Flat, with the playing one marked, rather than folded behind a
        // chevron. There are two outputs on a phone most of the time; one
        // of them is the answer and the other is the only alternative, so a
        // disclosure control costs a tap to reveal a single row.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            outputs.forEach { device ->
                OutputRow(
                    device = device,
                    accountName = accountName,
                    onSelect = { PlayerPlatform.host.selectAudioOutput(device.id) },
                )
            }
            // One more place to send the music, listed with the others. The
            // receivers behind it are chosen in a popup of their own — there
            // can be any number of them, and they come and go while it is open.
            // The watch, when one with BitChord on it is in reach: picked
            // directly, since there is only ever the one.
            if (watch.available || watch.active) {
                WatchRow(watch = watch, onClick = { PlayerPlatform.host.selectWatchOutput() })
            }
            if (cast.supported) CastRow(cast = cast, onClick = onOpenCast)
        }

        Spacer(Modifier.height(10.dp))
        // The phone's own stream first, then the level inside BitChord. Two
        // controls rather than one because they are two different things: the
        // system stream is shared with every other app and moves with the
        // hardware keys, while this one is this app's own output gain.
        VolumeRow(routeKey = outputs, label = stringResource(Res.string.system_volume))
        Spacer(Modifier.height(6.dp))
        // Hidden while a receiver or the watch is the speaker: the audio is not
        // in this process then, so a gain here would do nothing and read as
        // broken. The system row above stays — it is the control that does
        // still work for a Cast receiver.
        if (!remoteOutput) {
            InAppVolumeRow()
            Spacer(Modifier.height(6.dp))
        }
        AudioPipelineRow(onClick = onOpenPipeline)
    }
}

/**
 * Drills into [com.music.bitchord.ui.components.AudioPipelineDialog] — the
 * subtitle is the negotiated output itself, read live off
 * [AudioOutputStatus], so the row states what's actually leaving the phone
 * before anyone taps in for the rest of the chain.
 */
@Composable
private fun AudioPipelineRow(onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val outputFormat by PlayerPlatform.host.outputFormat.collectAsStateWithLifecycle()
    val subtitle = outputFormat.summary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(Haptic.Select)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.audio_pipeline),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Opens the receiver picker. Lit like the playing output while the music is on
 * a receiver, with that receiver's name in place of the generic title — at that
 * point it *is* the output, and the rows above it are the way back.
 */
@Composable
private fun CastRow(cast: CastUi, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val casting = cast.connectedName != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (casting) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(Haptic.Select)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (casting) 0.16f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (casting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (casting) 1f else 0.7f),
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = cast.connectedName ?: stringResource(Res.string.cast),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (casting) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = Color.White.copy(alpha = if (casting) 1f else 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(
                    when {
                        cast.connecting -> Res.string.cast_connecting
                        casting -> Res.string.cast_casting
                        else -> Res.string.cast_row_subtitle
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The BitChord watch app as the speaker. Drawn like an output of this phone —
 * lit, with a check, while it is the one playing — because to the listener that
 * is what it is.
 */
@Composable
private fun WatchRow(watch: WatchOutputUi, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val active = watch.active
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (active) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !active,
            ) {
                haptics.play(Haptic.Select)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (active) 0.16f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Watch,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (active) 1f else 0.7f),
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = watch.name?.takeIf(String::isNotBlank) ?: stringResource(Res.string.watch_output),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = Color.White.copy(alpha = if (active) 1f else 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(if (active) Res.string.audio_output_playing else Res.string.watch_output_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One output. The playing one is lit and says so; the rest are there to be
 * tapped.
 */
@Composable
private fun OutputRow(
    device: AudioOutputDevice,
    accountName: String?,
    onSelect: () -> Unit,
) {
    val haptics = rememberHaptics()
    val active = device.isActive
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = if (active) 0.10f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !active,
            ) {
                haptics.play(Haptic.Select)
                onSelect()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (active) 0.16f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = iconFor(device),
                contentDescription = null,
                tint = Color.White.copy(alpha = if (active) 1f else 0.7f),
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = outputLabel(device, accountName),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = Color.White.copy(alpha = if (active) 1f else 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.audio_output_playing),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
        if (active) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The phone's media volume, on the player's own slider rather than Material's.
 *
 * Two things move it, and only one of them announces itself. The hardware keys
 * broadcast `VOLUME_CHANGED_ACTION`, which is easy. A *route* change does not:
 * Android keeps one media volume index per output and silently swaps which one
 * is in force, so the number behind this bar changes with no event at all. Read
 * once at composition, as this was, the bar went on showing the level of the
 * device the listener had just moved away from.
 *
 * So [routeKey] — the outputs and the current choice — re-reads it, and re-reads
 * it again a beat later, because the framework swaps the index a moment after
 * the device list changes rather than in the same breath.
 */
@Composable
private fun VolumeRow(routeKey: Any, label: String) {
    val system = PlayerPlatform.host.volume
    val systemLevel by system.level.collectAsStateWithLifecycle()
    var level by remember(system) { mutableFloatStateOf(systemLevel) }
    var dragging by remember { mutableStateOf(false) }

    // The hardware keys move the system level; follow it, but never over a
    // finger: the listener's own drag is the one source of truth this must
    // not fight.
    LaunchedEffect(systemLevel) {
        if (!dragging) level = systemLevel
    }

    LaunchedEffect(routeKey) {
        repeat(VOLUME_REREADS) {
            if (!dragging) system.refresh()
            delay(VOLUME_REREAD_GAP_MS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        VolumeLabel(
            leading = {
                Icon(
                    imageVector = if (level > 0f) {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    } else {
                        Icons.AutoMirrored.Rounded.VolumeOff
                    },
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (level <= 0f) 0.45f else 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            },
            label = label,
            value = percentLabel(level),
            // The system control has no mute of its own: the hardware keys and
            // the system panel already own that, and a second one here would be
            // a third way to change the same number.
            dimmed = level <= 0f,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Same 20dp glyph column as the app row, so the two sliders line
            // up with each other and with the rows above.
            Spacer(Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            ThinSlider(
                value = level,
                onValueChange = {
                    dragging = true
                    level = it
                    system.set(it)
                },
                onValueChangeFinished = { dragging = false },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The level inside BitChord: a gain in our own DSP chain, so it is independent
 * of the system stream above and of the hardware keys.
 *
 * Kept visually parallel to [VolumeRow] — same capsule, same slider, same
 * glyph column — but distinguished by its glyph (an equaliser rather than a
 * speaker) and by a mute control the system row does not have. Muting keeps the
 * slider where the listener left it, so unmuting returns to that level rather
 * than to silence with the handle at zero.
 */
@Composable
private fun InAppVolumeRow() {
    val level by PlayerSettings.inAppVolume.collectAsStateWithLifecycle()
    val muted by PlayerSettings.inAppVolumeMuted.collectAsStateWithLifecycle()

    // Follow the finger exactly while dragging; otherwise read the setting, so
    // a change made anywhere else (a restore, a future shortcut) shows up here.
    var dragging by remember { mutableStateOf(false) }
    var dragLevel by remember { mutableFloatStateOf(level) }
    val shown = if (dragging) dragLevel else level
    val silent = muted || shown <= 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        VolumeLabel(
            leading = {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (silent) 0.45f else 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            },
            label = stringResource(
                if (muted) Res.string.in_app_volume_muted else Res.string.in_app_volume,
            ),
            value = percentLabel(shown),
            dimmed = silent,
            trailing = {
                // The flag, not the effective silence: at 0 % the row is
                // already silent, and a button labelled "Unmute" that set the
                // mute flag would do the opposite of what it says and leave the
                // listener with no way back except the slider.
                MuteButton(
                    muted = muted,
                    onClick = { PlayerSettings.setInAppVolumeMuted(!muted) },
                )
            },
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The glyph column the system row keeps empty, so the two sliders
            // start and end at the same x.
            Spacer(Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            ThinSlider(
                value = shown,
                onValueChange = {
                    dragging = true
                    dragLevel = it
                    // Dragging the handle up from a mute is the obvious way to
                    // unmute, and the alternative — a slider that moves while
                    // nothing is heard — reads as broken.
                    if (muted && it > 0f) PlayerSettings.setInAppVolumeMuted(false)
                    PlayerSettings.setInAppVolume(it)
                },
                onValueChangeFinished = { dragging = false },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * "72 %" — the figures beside a volume row's name.
 *
 * Built here rather than through a format argument: Compose's resource lookup
 * substitutes arguments without running them through `String.format`, so a
 * literal `%%` in the resource arrives as two percent signs.
 */
private fun percentLabel(level: Float): String =
    "${(level.coerceIn(0f, 1f) * 100).roundToInt()}%"

/** A speaker glyph that is also the mute switch. */
@Composable
private fun MuteButton(muted: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Icon(
        imageVector = if (muted) {
            Icons.AutoMirrored.Rounded.VolumeOff
        } else {
            Icons.AutoMirrored.Rounded.VolumeUp
        },
        contentDescription = stringResource(
            if (muted) Res.string.in_app_volume_unmute else Res.string.in_app_volume_mute,
        ),
        tint = Color.White.copy(alpha = if (muted) 0.45f else 0.75f),
        modifier = Modifier
            .size(20.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(if (muted) Haptic.ToggleOn else Haptic.ToggleOff)
                onClick()
            },
    )
}

/**
 * One line of a volume row: glyph, name, and the level in figures.
 *
 * The figures are the point. Two sliders with no numbers are two anonymous
 * bars, and the listener has no way to tell which one the hardware keys are
 * moving; a percentage beside each name says it without a paragraph.
 */
@Composable
private fun VolumeLabel(
    leading: @Composable () -> Unit,
    label: String,
    value: String,
    dimmed: Boolean,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = if (dimmed) 0.6f else 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.55f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

private fun iconFor(device: AudioOutputDevice): ImageVector = when (device.kind) {
    AudioOutputKind.PHONE -> Icons.Rounded.PhoneAndroid
    AudioOutputKind.WIRED -> Icons.Rounded.Headphones
    AudioOutputKind.USB -> Icons.Rounded.Usb
    // The same reading the output pill draws — see AudioOutputDevice.form.
    AudioOutputKind.BLUETOOTH -> when (device.form) {
        BluetoothForm.BUDS -> Icons.Rounded.Earbuds
        BluetoothForm.HEADPHONES -> Icons.Rounded.Headphones
        BluetoothForm.SPEAKER -> Icons.Rounded.Speaker
        BluetoothForm.CAR -> Icons.Rounded.DirectionsCar
        BluetoothForm.UNKNOWN -> Icons.Rounded.Bluetooth
    }
    AudioOutputKind.HDMI -> Icons.Rounded.Tv
    AudioOutputKind.OTHER -> Icons.Rounded.Speaker
}

/** How many times the level is re-read after a route change, and how far apart. */
private const val VOLUME_REREADS = 4
private const val VOLUME_REREAD_GAP_MS = 250L
