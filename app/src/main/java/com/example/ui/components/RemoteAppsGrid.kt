package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DeviceType
import com.example.data.model.TvAppEntity
import com.example.ui.theme.*

@Composable
fun RemoteAppsGrid(
    deviceType: DeviceType,
    deviceName: String,
    apps: List<TvAppEntity>,
    allAppsCount: Int,
    favoritesCount: Int,
    selectedCategory: String,
    searchQuery: String,
    onCategorySelected: (String) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onAppLaunch: (TvAppEntity) -> Unit,
    onToggleFavorite: (TvAppEntity) -> Unit,
    onDeleteApp: (TvAppEntity) -> Unit,
    onAddNewApp: (name: String, id: String, category: String, colorHex: Long, iconKey: String, isFav: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isAddDialogOpen by remember { mutableStateOf(false) }

    val categories = listOf(
        "Favorites",
        "All",
        "Movies & TV",
        "Streaming",
        "Live TV",
        "Music & Audio",
        "Tools",
        "Games"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_apps_grid_container"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Header: Device Scope & "+ Add App" Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Tus apps", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(
                    deviceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = { isAddDialogOpen = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(0.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("open_add_app_dialog_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Añadir", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = {
                Text(
                    "Buscar aplicaciones…",
                    fontSize = 14.sp,
                    color = TextMuted
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("app_search_input"),
            shape = RoundedCornerShape(0.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = RemoteBorderStrong,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Color.White,
                focusedContainerColor = Color(0xFF0C0C0C),
                unfocusedContainerColor = Color(0xFF0C0C0C)
            )
        )

        // Category Filter Chips (Brutalist Inverted Pills)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(categories) { cat ->
                val isSelected = selectedCategory == cat
                val isFavoritesTab = cat == "Favorites"

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        HapticUtil.triggerKeyHaptic(context)
                        onCategorySelected(cat)
                    },
                    leadingIcon = if (isFavoritesTab) {
                        {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = if (isSelected) Color.Black else TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    } else null,
                    label = {
                        val labelText = when (cat) {
                            "Favorites" -> "Favoritas ($favoritesCount)"
                            "All" -> "Todas ($allAppsCount)"
                            "Movies & TV" -> "Cine y TV"
                            "Live TV" -> "TV en vivo"
                            "Music & Audio" -> "Música"
                            "Tools" -> "Herramientas"
                            "Games" -> "Juegos"
                            else -> cat
                        }
                        Text(
                            text = labelText,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    shape = RoundedCornerShape(0.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color.Black,
                        containerColor = Color(0xFF0C0C0C),
                        labelColor = TextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = RemoteBorderStrong,
                        selectedBorderColor = Color.White
                    ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("filter_tab_${cat.lowercase().replace(" ", "_")}")
                )
            }
        }

        // Applications Grid: dynamically fills available viewport height
        if (apps.isEmpty()) {
            EmptyAppsState(
                isFavoritesFilter = selectedCategory == "Favorites",
                searchQuery = searchQuery,
                deviceType = deviceType,
                onClearFilter = {
                    onCategorySelected("All")
                    onSearchQueryChanged("")
                },
                onAddApp = { isAddDialogOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("tv_apps_grid"),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(apps, key = { it.id }) { app ->
                    TvAppGridCard(
                        app = app,
                        deviceType = deviceType,
                        onLaunch = {
                            HapticUtil.triggerKeyHaptic(context)
                            onAppLaunch(app)
                        },
                        onToggleFavorite = {
                            HapticUtil.triggerKeyHaptic(context)
                            onToggleFavorite(app)
                        },
                        onDelete = {
                            HapticUtil.triggerKeyHaptic(context)
                            onDeleteApp(app)
                        }
                    )
                }
            }
        }
    }

    // Add App Modal Dialog
    AddTvAppDialog(
        isOpen = isAddDialogOpen,
        deviceType = deviceType,
        onDismiss = { isAddDialogOpen = false },
        onConfirm = { name, id, category, colorHex, iconKey, isFav ->
            onAddNewApp(name, id, category, colorHex, iconKey, isFav)
        }
    )
}

@Composable
fun TvAppGridCard(
    app: TvAppEntity,
    deviceType: DeviceType,
    onLaunch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .border(
                width = 1.dp,
                color = RemoteBorder,
                shape = RoundedCornerShape(0.dp)
            )
            .clickable { onLaunch() }
            .testTag("app_card_${app.id}"),
        color = RemoteSurface,
        shape = RoundedCornerShape(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Row: App Icon & Favorite Toggle Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                PixelAppIcon(app)

                // Favorite Star Button
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("favorite_button_${app.id}")
                ) {
                    Icon(
                        imageVector = if (app.isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (app.isFavorite) "Quitar de favoritas" else "Guardar en favoritas",
                        tint = if (app.isFavorite) Color.White else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // App Name & Category
            Column {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = app.category,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextMuted,
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Action Row: Direct Launch Pill
            // Bottom Action Row: Direct Launch Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Launch badge (Brutalist stark pill)
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable { onLaunch() }
                        .testTag("launch_pill_${app.id}")
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Abrir",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                letterSpacing = 0.sp
                            )
                        )
                    }
                }

                // Delete option for custom apps or usage counter
                if (app.id > 10L) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Eliminar aplicación",
                            tint = Color(0xFFA3A3A3),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (app.launchCount > 0) {
                    Text(
                        text = "${app.launchCount}X",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = TextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyAppsState(
    isFavoritesFilter: Boolean,
    searchQuery: String,
    deviceType: DeviceType,
    onClearFilter: () -> Unit,
    onAddApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp),
        color = RemoteCardBg,
        shape = RoundedCornerShape(0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(RemoteSurfaceVariant, CircleShape)
                    .border(1.dp, RemoteBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFavoritesFilter) Icons.Default.Star else Icons.Default.Tv,
                    contentDescription = null,
                    tint = if (isFavoritesFilter) Color.White else TextMuted,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isFavoritesFilter) "Tus favoritas, a un toque" else if (searchQuery.isNotEmpty()) "Sin resultados para \"$searchQuery\"" else "Añade tu primera app",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isFavoritesFilter) "Marca una app con la estrella para tenerla siempre a mano." else "Abre tus apps directamente en ${deviceType.shortName}.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextMuted,
                    fontSize = 11.sp
                ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isFavoritesFilter || searchQuery.isNotEmpty()) {
                    Button(
                        onClick = onClearFilter,
                        colors = ButtonDefaults.buttonColors(containerColor = RemoteBtnDefault),
                        shape = RoundedCornerShape(0.dp)
                    ) {
                        Text("Ver todas", color = TextPrimary, fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = onAddApp,
                    colors = ButtonDefaults.buttonColors(containerColor = deviceType.brandColor),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Text("+ Añadir app", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

fun getAppIconVector(iconKey: String, appName: String): ImageVector {
    val nameLower = appName.lowercase()
    return when {
        nameLower.contains("youtube") -> Icons.Default.PlayCircle
        nameLower.contains("spotify") || nameLower.contains("music") || iconKey == "music" -> Icons.Default.MusicNote
        nameLower.contains("twitch") || nameLower.contains("game") || iconKey == "game" -> Icons.Default.SportsEsports
        nameLower.contains("browser") || nameLower.contains("chrome") || iconKey == "browser" -> Icons.Default.Language
        nameLower.contains("tv") || nameLower.contains("kodi") || iconKey == "tv" -> Icons.Default.Tv
        nameLower.contains("launch") || iconKey == "rocket" -> Icons.Default.RocketLaunch
        else -> Icons.Default.Movie
    }
}
