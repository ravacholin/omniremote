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
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "TV APPLICATIONS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(deviceType.brandColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .border(0.5.dp, deviceType.brandColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = deviceType.shortName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = deviceType.brandColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                Text(
                    text = "$favoritesCount favorites • $allAppsCount total on $deviceName",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 11.sp
                    ),
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
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("open_add_app_dialog_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "ADD APP",
                    color = Color.Black,
                    fontWeight = FontWeight.Black,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }

        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = {
                Text(
                    "Search TV apps...",
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
                .height(48.dp)
                .testTag("app_search_input"),
            shape = RoundedCornerShape(8.dp),
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
                            "Favorites" -> "FAV ($favoritesCount)"
                            "All" -> "ALL ($allAppsCount)"
                            else -> cat.uppercase()
                        }
                        Text(
                            text = labelText,
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
                        )
                    },
                    shape = RoundedCornerShape(6.dp),
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
                    modifier = Modifier.testTag("filter_tab_${cat.lowercase().replace(" ", "_")}")
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
                columns = GridCells.Adaptive(minSize = 145.dp),
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
    val appIcon = getAppIconVector(app.iconKey, app.appName)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (app.isFavorite) 1.5.dp else 1.dp,
                color = if (app.isFavorite) Color.White else RemoteBorderStrong,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onLaunch() }
            .testTag("app_card_${app.id}"),
        color = Color(0xFF0C0C0C),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Row: App Icon & Favorite Toggle Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Monochromatic App Icon Container
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFF181818), RoundedCornerShape(6.dp))
                        .border(1.dp, RemoteBorderStrong, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = appIcon,
                        contentDescription = app.appName,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Favorite Star Button
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("favorite_button_${app.id}")
                ) {
                    Icon(
                        imageVector = if (app.isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (app.isFavorite) "Unstar favorite" else "Star favorite",
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
                    color = Color.White,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .clickable { onLaunch() }
                        .testTag("launch_pill_${app.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Launch",
                            tint = Color.Black,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "OPEN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.Black,
                                fontWeight = FontWeight.Black,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }

                // Delete option for custom apps or usage counter
                if (app.id > 10L) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete app",
                            tint = Color(0xFFA1A1AA),
                            modifier = Modifier.size(14.dp)
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
        shape = RoundedCornerShape(20.dp),
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
                    .background(Color(0xFF1E293B), CircleShape)
                    .border(1.dp, RemoteBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFavoritesFilter) Icons.Default.Star else Icons.Default.Tv,
                    contentDescription = null,
                    tint = if (isFavoritesFilter) Color(0xFFF59E0B) else TextMuted,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isFavoritesFilter) "No Favorite Apps Yet" else if (searchQuery.isNotEmpty()) "No Apps Match \"$searchQuery\"" else "No Apps Stored",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isFavoritesFilter) "Tap the star (⭐) on any app card to bookmark it to your favorites list!" else "Add your favorite streaming or utility apps to quick-launch them on ${deviceType.shortName}.",
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
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("View All Apps", color = TextPrimary, fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = onAddApp,
                    colors = ButtonDefaults.buttonColors(containerColor = deviceType.brandColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("+ Add App", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
