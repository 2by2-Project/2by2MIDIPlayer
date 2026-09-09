@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.project2by2.musicplayer.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.*
import jp.project2by2.musicplayer.ui.player.playerString
import androidx.compose.foundation.selection.selectable

@Composable
fun SettingsScreen(
    soundFontName: String?, hasSoundFont: Boolean, maxVoices: Int, effectsEnabled: Boolean,
    reverbStrength: Float, loopEnabled: Boolean, shuffleEnabled: Boolean,
    onBack: () -> Unit, onPickSoundFont: () -> Unit,
    onRecommendedSoundFonts: (() -> Unit)? = null, onMaxVoicesChange: (Int) -> Unit,
    onEffectsChange: (Boolean) -> Unit, onReverbChange: (Float) -> Unit,
    onLoopChange: (Boolean) -> Unit, onShuffleChange: (Boolean) -> Unit,
    soundFontLoading: Boolean = false
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playerString("settings")) },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = playerString("back"))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp)
            ) {
                // MIDI Synthesizer
                item {
                    Text(playerString("settings_category_midi_synthesizer"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                }
                item {
                    val label = soundFontName ?: playerString("settings_soundfont_loaded")
                    SettingsInfoItem(title = playerString("settings_soundfont_title"), value = if (hasSoundFont) label else playerString("settings_soundfont_not_set"))
                    Button(
                        onClick = { onPickSoundFont() }, enabled = !soundFontLoading,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text(playerString("settings_soundfont_load_button"))
                    }
                    // Recommended soundfonts
                    if (onRecommendedSoundFonts != null) TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        onClick = { onRecommendedSoundFonts?.invoke() }, enabled = !soundFontLoading,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(playerString("settings_soundfont_recommended_button"))
                    }
                }
                item {
                    
                    SettingsDropdownItem(
                        title = playerString("settings_max_voices_title"),
                        options = listOf("20", "40", "100", "200"),
                        defaultValue = maxVoices.toString(),
                        onSelectedChange = { onMaxVoicesChange(it.toIntOrNull() ?: 40) }
                    )
                }
                item {
                    SettingsSwitchItem(
                        title = playerString("settings_effects_toggle_title"),
                        checked = effectsEnabled,
                        onCheckedChange = onEffectsChange
                    )
                }
                item {
                    AnimatedVisibility(
                        visible = effectsEnabled,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp)
                            ) {
                                SettingsSliderItem(
                                    title = playerString("settings_effects_reverb_strength"),
                                    value = reverbStrength,
                                    enabled = effectsEnabled,
                                    valueRange = 0.0f..3.0f,
                                    onValueChange = onReverbChange
                                )
                            }
                        }
                    }
                }
                // Playback
                item {
                    Text(playerString("settings_category_playback"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                }
                item {
                    SettingsSwitchItem(
                        title = playerString("settings_playback_loop_toggle"),
                        checked = loopEnabled,
                        onCheckedChange = onLoopChange
                    )
                }
                item {
                    SettingsSwitchItem(
                        title = playerString("settings_playback_shuffle_toggle"),
                        checked = shuffleEnabled,
                        onCheckedChange = onShuffleChange
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsInfoItem(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsSliderItem(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true
) {
    var uiValue by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value) {
        uiValue = value
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        Slider(
            value = uiValue,
            onValueChange = { v ->
                uiValue = v
            },
            onValueChangeFinished = {
                onValueChange(uiValue)
            },
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}


@Composable
private fun SettingsRadioItem(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = { if (enabled) onClick() else null },
                role = androidx.compose.ui.semantics.Role.RadioButton
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = null
        )
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdownItem(
    title: String,
    options: List<String>,
    defaultValue: String,
    onSelectedChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(defaultValue) { mutableStateOf(defaultValue) }

    LaunchedEffect(defaultValue) {
        if (defaultValue in options) {
            selected = defaultValue
        } else {
            selected = options.first()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            TextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .widthIn(min = 80.dp, max = 180.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            selected = option
                            onSelectedChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

