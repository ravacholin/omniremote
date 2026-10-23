package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeviceType
import com.example.data.model.StreamingAppCatalog
import com.example.ui.theme.RemoteBorder
import com.example.ui.theme.RemoteCardBg
import com.example.ui.theme.RemoteDarkBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.*

data class AppPreset(
    val name: String,
    val samsungId: String,
    val androidPkg: String,
    val category: String,
    val colorHex: Long,
    val iconKey: String
)

val POPULAR_PRESETS = StreamingAppCatalog.apps.map {
    AppPreset(it.name, it.samsungId, it.androidPackage, it.category, it.colorHex, it.iconKey)
} + listOf(
    AppPreset("Hulu", "3201601007625", "com.hulu.livingroomplus", "Movies & TV", 0xFF1CE783, "movie"),
    AppPreset("Peacock", "3202010022098", "com.peacocktv.peacockandroid", "Movies & TV", 0xFF000000, "movie"),
    AppPreset("Crunchyroll", "3201601007270", "com.crunchyroll.crunchyroid", "Movies & TV", 0xFFF47521, "movie"),
    AppPreset("Kodi", "org.xbmc.kodi", "org.xbmc.kodi", "Tools", 0xFF13B5EA, "tv"),
    AppPreset("VLC Player", "org.videolan.vlc", "org.videolan.vlc", "Tools", 0xFFFF8800, "movie"),
    AppPreset("Tidal Music", "3201805016367", "com.aspiro.tidal", "Music & Audio", 0xFF000000, "music"),
    AppPreset("Steam Link", "3201704012196", "com.valvesoftware.steamlink", "Games", 0xFF171A21, "game")
)

val APP_COLORS = listOf(
    0xFFE50914, // Netflix Red
    0xFFFF0000, // YouTube Red
    0xFF00A8E1, // Prime Cyan
    0xFF113CCF, // Disney Blue
    0xFF1DB954, // Spotify Green
    0xFF9146FF, // Twitch Purple
    0xFF38BDF8, // Browser Sky
    0xFFF59E0B, // Amber
    0xFF10B981, // Emerald
    0xFFEC4899  // Pink
)

val APP_CATEGORIES = listOf("Movies & TV", "Streaming", "Music & Audio", "Tools", "Games", "Live TV")

@Composable
fun AddTvAppDialog(
    isOpen: Boolean,
    deviceType: DeviceType,
    onDismiss: () -> Unit,
    onConfirm: (name: String, id: String, category: String, colorHex: Long, iconKey: String, isFav: Boolean) -> Unit
) {
    if (!isOpen) return

    var appName by remember { mutableStateOf("") }
    var appIdentifier by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Movies & TV") }
    var selectedColor by remember { mutableLongStateOf(APP_COLORS[0]) }
    var iconKey by remember { mutableStateOf("movie") }
    var isFavorite by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RemoteCardBg,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Add App to ${deviceType.shortName}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = if (deviceType == DeviceType.SAMSUNG_TIZEN) "Tizen Smart TV Application" else "Android TV / Google TV App",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = deviceType.brandColor,
                            fontSize = 11.sp
                        )
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Quick Presets Row
                Text(
                    text = "QUICK POPULAR PRESETS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(POPULAR_PRESETS) { preset ->
                        Surface(
                            modifier = Modifier
                                .clip(Sharp)
                                .border(1.dp, RemoteBorder, Sharp)
                                .clickable {
                                    appName = preset.name
                                    appIdentifier = if (deviceType == DeviceType.SAMSUNG_TIZEN) preset.samsungId else preset.androidPkg
                                    category = preset.category
                                    selectedColor = preset.colorHex
                                    iconKey = preset.iconKey
                                }
                                .testTag("preset_${preset.name.lowercase().replace(" ", "_")}"),
                            color = RemoteDarkBg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(preset.colorHex), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        }
                    }
                }

                // App Name
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("App Name") },
                    placeholder = { Text("e.g. Hulu, HBO Max, Kodi") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_app_name"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = deviceType.brandColor,
                        unfocusedBorderColor = RemoteBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = deviceType.brandColor,
                        unfocusedLabelColor = TextMuted
                    ),
                    singleLine = true
                )

                // App Identifier (Samsung Tizen App ID or Android Package Name)
                OutlinedTextField(
                    value = appIdentifier,
                    onValueChange = { appIdentifier = it },
                    label = {
                        Text(if (deviceType == DeviceType.SAMSUNG_TIZEN) "Tizen App ID (or App Name)" else "Package Name (e.g. com.example.tv)")
                    },
                    placeholder = {
                        Text(if (deviceType == DeviceType.SAMSUNG_TIZEN) "e.g. 3201601007625" else "e.g. com.hulu.livingroomplus")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_app_identifier"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = deviceType.brandColor,
                        unfocusedBorderColor = RemoteBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = deviceType.brandColor,
                        unfocusedLabelColor = TextMuted
                    ),
                    singleLine = true
                )

                // Category Selection
                Text(
                    text = "CATEGORY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(APP_CATEGORIES) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = deviceType.brandColor.copy(alpha = 0.2f),
                                selectedLabelColor = deviceType.brandColor
                            )
                        )
                    }
                }

                // Color Selection
                Text(
                    text = "BRAND COLOR ACCENT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    APP_COLORS.take(7).forEach { colorLong ->
                        val color = Color(colorLong)
                        val isSelected = selectedColor == colorLong
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color.White else RemoteBorder,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = colorLong }
                        )
                    }
                }

                // Add to Favorites Switch
                Surface(
                    color = RemoteDarkBg,
                    shape = Sharp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                                contentDescription = null,
                                tint = if (isFavorite) Paper else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Mark as Favorite (⭐)",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                        Switch(
                            checked = isFavorite,
                            onCheckedChange = { isFavorite = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Void, checkedTrackColor = Paper)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (appName.isNotBlank() && appIdentifier.isNotBlank()) {
                        onConfirm(appName, appIdentifier, category, selectedColor, iconKey, isFavorite)
                        onDismiss()
                    }
                },
                enabled = appName.isNotBlank() && appIdentifier.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = deviceType.brandColor),
                shape = Sharp,
                modifier = Modifier.testTag("confirm_add_app_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add to TV", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = Sharp) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
