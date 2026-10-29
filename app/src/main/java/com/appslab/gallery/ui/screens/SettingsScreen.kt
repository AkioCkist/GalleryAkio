package com.appslab.gallery.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.appslab.gallery.BuildConfig
import com.appslab.gallery.R
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.appslab.gallery.data.DateFormatOption
import com.appslab.gallery.data.GridSpacing
import com.appslab.gallery.data.GridStyle
import com.appslab.gallery.data.MediaFilterType
import com.appslab.gallery.data.SortOption
import com.appslab.gallery.ui.GalleryViewModel
import com.appslab.gallery.ui.theme.AppTheme
import com.appslab.gallery.ui.theme.CqMono
import com.appslab.gallery.ui.theme.LocalCustomColors
import com.appslab.gallery.ui.theme.swatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsDialog { SORT, FILTER, DATE, RESET }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val repo = viewModel.settingsRepo
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("SETTINGS", fontFamily = CqMono, fontSize = 22.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("PERSONALIZE YOUR GALLERY") }
            item {
                SettingCard(painterResource(R.drawable.bright), "Dark mode", if (s.darkMode) "On" else "Off") {
                    Switch(checked = s.darkMode, onCheckedChange = repo::setDarkMode)
                }
            }
            item { ThemePicker(selected = s.theme, dark = s.darkMode, onSelect = repo::setTheme) }

            item { SectionHeader("LAYOUT") }
            item { LayoutPicker(selected = s.gridStyle, dark = s.darkMode, onSelect = repo::setGridStyle) }
            item {
                Surface(color = LocalCustomColors.current.cardBackground, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 14.dp)) {
                        Text("Spacing · ${s.gridSpacing.resolve(s.gridStyle)} dp", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(10.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(GridSpacing.entries) { sp ->
                                FilterChip(
                                    selected = sp == s.gridSpacing,
                                    onClick = { repo.setGridSpacing(sp) },
                                    label = { Text(sp.label, fontFamily = CqMono) },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = Color.White,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SettingCard(painterResource(R.drawable.date), "Date headers", "Group photos under TODAY, YESTERDAY and dates") {
                    Switch(checked = s.showDateHeaders, onCheckedChange = repo::setShowDateHeaders)
                }
            }
            item {
                SettingCard(painterResource(R.drawable.video1), "Video duration", "Show length on video tiles") {
                    Switch(checked = s.showVideoDuration, onCheckedChange = repo::setShowVideoDuration)
                }
            }
            item { SectionHeader("GALLERY") }
            item {
                SettingCard(painterResource(R.drawable.order1), "Sort photos", s.sortOption.label, onClick = { dialog = SettingsDialog.SORT })
            }
            item {
                SettingCard(
                    painterResource(R.drawable.filters), "Filter Media Types",
                    if (s.mediaFilters.isEmpty()) "Showing all media types" else s.mediaFilters.joinToString { it.label },
                    onClick = { dialog = SettingsDialog.FILTER },
                )
            }
            item {
                SettingCard(painterResource(R.drawable.date), "Date format", s.dateFormat.example, onClick = { dialog = SettingsDialog.DATE })
            }

            item { SectionHeader("VIEWING") }
            item {
                SettingCard(painterResource(R.drawable.bright), "Max brightness viewing", "Full brightness while viewing photos") {
                    Switch(checked = s.maxBrightnessViewing, onCheckedChange = repo::setMaxBrightnessViewing)
                }
            }
            item {
                SettingCard(painterResource(R.drawable.video1), "Remember video position", "Resume videos where you left off") {
                    Switch(checked = s.rememberVideoPosition, onCheckedChange = repo::setRememberVideoPosition)
                }
            }

            item { SectionHeader("AI SEARCH") }
            item {
                SettingCard(
                    painterResource(R.drawable.vault1), "AI search",
                    if (s.ai.enabled) "Find photos by content, faces and text, on-device" else "Off · search by file name only",
                ) {
                    Switch(checked = s.ai.enabled, onCheckedChange = { repo.setAi(s.ai.copy(enabled = it)) })
                }
            }
            if (s.ai.enabled) {
                item {
                    SettingCard(painterResource(R.drawable.people__1_), "Face detection", "People view and face filters") {
                        Switch(checked = s.ai.faces, onCheckedChange = { repo.setAi(s.ai.copy(faces = it)) })
                    }
                }
                item {
                    SettingCard(painterResource(R.drawable.doc), "Text in photos (OCR)", "Search words in screenshots and documents · slowest part") {
                        Switch(checked = s.ai.text, onCheckedChange = { repo.setAi(s.ai.copy(text = it)) })
                    }
                }
                item {
                    SettingCard(painterResource(R.drawable.bright), "Only while charging", "Analyse new photos only when plugged in") {
                        Switch(checked = s.ai.onlyWhileCharging, onCheckedChange = { repo.setAi(s.ai.copy(onlyWhileCharging = it)) })
                    }
                }
            }
            item {
                val indexer = (context.applicationContext as com.appslab.gallery.GalleryApplication).mediaIndexer
                SettingCard(painterResource(R.drawable.del123), "Clear AI data", "Delete the search index · re-analyses if AI search is on", onClick = {
                    scope.launch {
                        indexer.clear()
                        Toast.makeText(context, "AI data cleared", Toast.LENGTH_SHORT).show()
                    }
                })
            }

            item { SectionHeader("STORAGE") }
            item {
                SettingCard(painterResource(R.drawable.ref), "Clear cache", "Free space used by thumbnails", onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                SingletonImageLoader.get(context).let { loader ->
                                    loader.memoryCache?.clear()
                                    loader.diskCache?.clear()
                                }
                                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                            }.isSuccess
                        }
                        Toast.makeText(context, if (ok) "Cache cleared successfully" else "Error clearing cache", Toast.LENGTH_SHORT).show()
                    }
                })
            }

            item {
                SettingCard(
                    painterResource(R.drawable.restore), "Restore default settings",
                    "Undo every settings change · photos and favorites are kept",
                    onClick = { dialog = SettingsDialog.RESET },
                )
            }

            item { SectionHeader("ABOUT") }
            item {
                SettingCard(painterResource(R.drawable.info), "GalleryAkio", "Version ${BuildConfig.VERSION_NAME}")
            }
            item {
                SettingCard(painterResource(R.drawable.contact), "Support", "help.appslab@gmail.com", onClick = {
                    context.openSafely(Intent(Intent.ACTION_SENDTO, "mailto:help.appslab@gmail.com".toUri()).apply {
                        putExtra(Intent.EXTRA_SUBJECT, "GalleryAkio Support")
                    }, "No email app found")
                })
            }
            item {
                SettingCard(painterResource(R.drawable.secure), "Privacy policy", null, onClick = {
                    context.openSafely(
                        Intent(Intent.ACTION_VIEW, "https://www.termsfeed.com/live/a9edf645-e46c-4e2d-9305-2b074a6a52c0".toUri()),
                        "Unable to open privacy policy",
                    )
                })
            }
        }
    }

    when (dialog) {
        SettingsDialog.RESET -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Restore defaults?", fontFamily = CqMono) },
            text = {
                Text(
                    "Theme, layout, sorting, filters, viewing and AI search settings go back to how the app first came. " +
                        "Your photos, favorites and pinned albums are not touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.resetToDefaults()
                    dialog = null
                    Toast.makeText(context, "Settings restored to default", Toast.LENGTH_SHORT).show()
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        SettingsDialog.SORT -> ChoiceDialog(
            title = "Sort photos",
            options = SortOption.entries,
            selected = s.sortOption,
            label = { it.label },
            description = { it.description },
            onSelect = { option -> repo.setSortOption(option); if (option == SortOption.RANDOM) viewModel.reshuffle() },
            onDismiss = { dialog = null },
        )
        SettingsDialog.DATE -> ChoiceDialog(
            title = "Date format",
            options = DateFormatOption.entries,
            selected = s.dateFormat,
            label = { "Example: ${it.example}" },
            onSelect = repo::setDateFormat,
            onDismiss = { dialog = null },
        )
        SettingsDialog.FILTER -> FilterDialog(
            selected = s.mediaFilters,
            onApply = { repo.setMediaFilters(it); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun Context.openSafely(intent: Intent, error: String) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, fontFamily = CqMono, fontSize = 12.sp, letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun SettingCard(
    icon: Painter,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        color = LocalCustomColors.current.cardBackground,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = LocalCustomColors.current.unselectedItem)
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun ThemePicker(selected: AppTheme, dark: Boolean, onSelect: (AppTheme) -> Unit) {
    Surface(color = LocalCustomColors.current.cardBackground, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text("Theme · ${selected.label}", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(AppTheme.entries) { theme ->
                    val isSelected = theme == selected
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(theme.swatch(dark))
                            .border(BorderStroke(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface), CircleShape)
                            .clickable { onSelect(theme) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) Icon(Icons.Filled.Check, null, tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Horizontal strip of layout cards, each with a miniature drawing of the style. */
@Composable
private fun LayoutPicker(selected: GridStyle, dark: Boolean, onSelect: (GridStyle) -> Unit) {
    Surface(color = LocalCustomColors.current.cardBackground, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text("Grid style · ${selected.label}", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text(selected.description, fontSize = 12.sp, color = LocalCustomColors.current.unselectedItem, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(GridStyle.entries) { style ->
                    val isSelected = style == selected
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onSelect(style) }.padding(4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(width = 84.dp, height = 112.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (dark) Color(0xFF121212) else Color(0xFFFAFAFA))
                                .border(
                                    BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else LocalCustomColors.current.divider),
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(6.dp),
                        ) {
                            StylePreview(style, dark)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            style.label.uppercase(), fontFamily = CqMono, fontSize = 12.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StylePreview(style: GridStyle, dark: Boolean) {
    val tile = if (dark) Color(0xFF5A5A5A) else Color(0xFFB0B0B0)
    val border = if (dark) Color(0xFF9A9A9A) else Color(0xFF707070)
    val gap = (style.defaultSpacing / 2f).coerceAtLeast(1f).dp
    val shape = RoundedCornerShape((style.cornerRadius / 4f).dp)
    @Composable
    fun Tile(modifier: Modifier) = Box(
        modifier.clip(shape).background(tile).let { if (style.border) it.border(0.5.dp, border, shape) else it },
    )
    when (style) {
        GridStyle.LIST -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(5) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tile(Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.size(40.dp, 3.dp).background(tile))
                        Box(Modifier.size(26.dp, 3.dp).background(tile.copy(alpha = 0.6f)))
                    }
                }
            }
        }
        GridStyle.MASONRY -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(listOf(30, 18, 26, 22), listOf(20, 32, 16, 30)).forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    col.forEach { h -> Tile(Modifier.fillMaxWidth().height(h.dp)) }
                }
            }
        }
        GridStyle.MOSAIC -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            listOf(listOf(1.5f, 0.8f), listOf(0.7f, 0.7f, 1.2f), listOf(1f, 1.6f), listOf(0.8f, 1.3f, 0.7f)).forEach { row ->
                Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { w -> Tile(Modifier.weight(w).fillMaxHeight()) }
                }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            repeat(4) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(3) { Tile(Modifier.weight(1f).aspectRatio(1f)) }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: ((T) -> String)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = CqMono) },
        text = {
            LazyColumn {
                items(options) { option ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSelect(option); onDismiss() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option); onDismiss() })
                        Column {
                            Text(label(option))
                            description?.let { Text(it(option), fontSize = 12.sp, color = LocalCustomColors.current.unselectedItem) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun FilterDialog(selected: Set<MediaFilterType>, onApply: (Set<MediaFilterType>) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(selected.ifEmpty { MediaFilterType.entries.toSet() }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Media Types", fontFamily = CqMono) },
        text = {
            Column {
                Text("Select media types to display", fontSize = 12.sp)
                MediaFilterType.entries.forEach { type ->
                    Row(
                        Modifier.fillMaxWidth().clickable { current = if (type in current) current - type else current + type },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = type in current, onCheckedChange = { current = if (it) current + type else current - type })
                        Text(type.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = current.isNotEmpty(),
                // Everything selected is stored as "no filter".
                onClick = { onApply(if (current.size == MediaFilterType.entries.size) emptySet() else current) },
            ) { Text("Done") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
