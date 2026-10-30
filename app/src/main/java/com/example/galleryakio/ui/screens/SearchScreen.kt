package com.example.galleryakio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.galleryakio.R
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.search.IndexProgress
import com.example.galleryakio.data.search.SearchCategory
import com.example.galleryakio.ui.CategorySummary
import com.example.galleryakio.ui.FaceFilter
import com.example.galleryakio.ui.GalleryViewModel
import com.example.galleryakio.ui.MediaSource
import com.example.galleryakio.ui.Routes
import com.example.galleryakio.ui.SearchViewModel
import com.example.galleryakio.ui.components.MediaGrid
import com.example.galleryakio.ui.components.MediaThumbnail
import com.example.galleryakio.ui.components.SelectionState
import com.example.galleryakio.ui.components.rememberCellSizePx
import com.example.galleryakio.ui.theme.CqMono
import com.example.galleryakio.ui.theme.LocalCustomColors

/** null = home; "people" = face view; otherwise a [SearchCategory] name. */
private const val PEOPLE = "people"

@Composable
fun SearchScreen(galleryViewModel: GalleryViewModel, navController: NavController) {
    val search: SearchViewModel = viewModel()
    val library by galleryViewModel.library.collectAsStateWithLifecycle()
    val settings by galleryViewModel.settings.collectAsStateWithLifecycle()
    val query by search.query.collectAsStateWithLifecycle()
    val results by search.results.collectAsStateWithLifecycle()
    val progress by search.progress.collectAsStateWithLifecycle()
    val categories by search.categories.collectAsStateWithLifecycle()
    val ai by search.aiSettings.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf<String?>(null) }

    // Index the whole library (not the filtered view) and keep it in sync.
    LaunchedEffect(library.all, library.isLoading) {
        if (!library.isLoading) search.onLibrary(library.all)
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LocalHeaderPadding.current
    fun open(item: MediaItem, list: List<MediaItem>) {
        galleryViewModel.customList = list
        navController.navigate(Routes.photo(item.id, MediaSource.Custom))
    }

    Column(Modifier.fillMaxSize().padding(top = top)) {
        when {
            view == PEOPLE -> PeopleView(search, settings.gridColumns, settings.darkMode, ::open, onBack = { view = null })
            view != null -> {
                val summary = categories.firstOrNull { it.category.name == view }
                if (summary == null) view = null
                else CategoryView(summary, settings.gridColumns, settings.darkMode, ::open, onBack = { view = null })
            }
            else -> {
                SearchBar(query, onChange = { search.query.value = it }, onClear = { search.query.value = "" })
                if (query.isBlank()) {
                    SearchHome(
                        aiEnabled = ai.enabled,
                        progress = progress,
                        categories = categories,
                        onAction = { action ->
                            when (action) {
                                "trash" -> navController.navigate(Routes.TRASH)
                                "favorite" -> navController.navigate(Routes.FAVORITES)
                                PEOPLE -> if (ai.enabled && ai.faces) view = PEOPLE else navController.navigate(Routes.SETTINGS)
                                // The AI button opens its settings, like the original AI screen entry.
                                "ai" -> navController.navigate(Routes.SETTINGS)
                            }
                        },
                        onCategory = { view = it.name },
                    )
                } else {
                    ResultsView(results.byName, results.byContent, progress, settings.gridColumns, settings.darkMode, ::open)
                }
            }
        }
    }
}

/** Port of the original SearchBar: 28 dp pill, dot-matrix "Search here" hint, clear button. */
@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onClear: () -> Unit) {
    val focus = LocalFocusManager.current
    val colors = LocalCustomColors.current
    Surface(
        color = colors.cardBackground,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).height(56.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, "Search", tint = colors.unselectedItem, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search here", fontFamily = CqMono, fontSize = 16.sp, color = colors.unselectedItem)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "Clear search", tint = colors.unselectedItem, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private data class ActionButton(val title: String, val icon: Int, val color: Color, val route: String)

// Colours from the original ActionButton list; VAULT became PEOPLE (face search).
private val Actions = listOf(
    ActionButton("TRASH", R.drawable.del123, Color(0xFF925AFF), "trash"),
    ActionButton("FAVORITE", R.drawable.fav123, Color(0xFFFF2F5A), "favorite"),
    ActionButton("PEOPLE", R.drawable.people__1_, Color(0xFF199DBC), PEOPLE),
    ActionButton("AI", R.drawable.vault1, Color(0xFF1283FF), "ai"),
)

@Composable
private fun SearchHome(
    aiEnabled: Boolean,
    progress: IndexProgress,
    categories: List<CategorySummary>,
    onAction: (String) -> Unit,
    onCategory: (SearchCategory) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Actions.forEach { action ->
                    ActionButtonItem(
                        action,
                        label = action.title,
                        onClick = { onAction(action.route) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (!aiEnabled) {
            item {
                InfoCard(
                    "AI SEARCH IS OFF",
                    "Searching by file name only. Turn on AI search in Settings to find photos by content, faces and text.",
                    onClick = { onAction("ai") },
                )
            }
            return@LazyColumn
        }
        item { AnalysisCard(progress, onClick = { onAction("ai") }) }
        item {
            Text(
                "CATEGORIES", fontFamily = CqMono, fontSize = 14.sp,
                color = LocalCustomColors.current.unselectedItem,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
        items(categories.filter { it.items.isNotEmpty() }, key = { it.category.name }) { summary ->
            CategoryCard(summary, onClick = { onCategory(summary.category) })
        }
        if (categories.none { it.items.isNotEmpty() } && !progress.running) {
            item {
                Text(
                    "Images will appear here as they're categorized",
                    color = LocalCustomColors.current.unselectedItem,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

/** Port of ActionButtonItem: 77 dp tall, 22 dp corners, coloured icon over a dot-matrix label. */
@Composable
private fun ActionButtonItem(action: ActionButton, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = LocalCustomColors.current.cardBackground,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier.height(77.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(40.dp).background(action.color, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(action.icon), action.title, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(label, fontFamily = CqMono, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, onClick: () -> Unit) {
    Surface(
        color = LocalCustomColors.current.cardBackground,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontFamily = CqMono, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(body, fontSize = 12.sp, color = LocalCustomColors.current.unselectedItem)
        }
    }
}

@Composable
private fun AnalysisCard(progress: IndexProgress, onClick: () -> Unit) {
    if (progress.waitingForCharger) {
        InfoCard(
            "WAITING FOR CHARGER",
            "Analysis runs only while charging (${progress.done}/${progress.total} done). Change this in Settings.",
            onClick,
        )
        return
    }
    if (progress.total == 0) return
    Surface(
        color = LocalCustomColors.current.cardBackground,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (progress.running) "ANALYZING..." else "ANALYSIS COMPLETE",
                    fontFamily = CqMono, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text("${progress.done}/${progress.total}", fontFamily = CqMono, fontSize = 14.sp, color = LocalCustomColors.current.unselectedItem)
            }
            if (progress.running) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = LocalCustomColors.current.divider,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "On-device AI: labels, faces and text. Nothing leaves your phone.",
                    fontSize = 11.sp, color = LocalCustomColors.current.unselectedItem,
                )
            }
        }
    }
}

/** Port of CategoryCardSearch: 24 dp card, 56 dp coloured circle, name, count and a preview strip. */
@Composable
private fun CategoryCard(summary: CategorySummary, onClick: () -> Unit) {
    val cat = summary.category
    val cellPx = rememberCellSizePx(5, LocalConfiguration.current.screenWidthDp)
    Surface(
        color = LocalCustomColors.current.cardBackground,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).background(cat.color, RoundedCornerShape(50.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(cat.icon), cat.title, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        cat.title.uppercase(), fontFamily = CqMono, fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${summary.items.size} images", fontSize = 14.sp, color = LocalCustomColors.current.unselectedItem)
                    if (summary.avgConfidence > 0f) {
                        Text(
                            "Avg confidence: ${(summary.avgConfidence * 100).toInt()}%",
                            fontSize = 12.sp, color = LocalCustomColors.current.unselectedItem,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = false) {
                items(summary.items.take(4), key = { it.id }) { item ->
                    MediaThumbnail(
                        item, cellPx,
                        Modifier.size(64.dp).aspectRatio(1f).clip(RoundedCornerShape(18.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewHeader(title: String, count: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground) }
        Text(title.uppercase(), fontFamily = CqMono, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Text("$count", fontFamily = CqMono, fontSize = 16.sp, color = LocalCustomColors.current.unselectedItem, modifier = Modifier.padding(end = 16.dp))
    }
}

@Composable
private fun CategoryView(
    summary: CategorySummary,
    columns: Int,
    darkMode: Boolean,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onBack: () -> Unit,
) {
    val selection = remember { SelectionState() }
    ViewHeader(summary.category.title, summary.items.size, onBack)
    MediaGrid(
        items = summary.items,
        columns = columns,
        showDateHeaders = false,
        dateFormat = com.example.galleryakio.data.DateFormatOption.FORMAT_FULL_DMY,
        selection = selection,
        onOpen = { onOpen(it, summary.items) },
        darkMode = darkMode,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PeopleView(
    search: SearchViewModel,
    columns: Int,
    darkMode: Boolean,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onBack: () -> Unit,
) {
    val people by search.people.collectAsStateWithLifecycle()
    val filter by search.faceFilter.collectAsStateWithLifecycle()
    val selection = remember { SelectionState() }
    ViewHeader("People & Faces", people.size, onBack)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        items(FaceFilter.entries) { f ->
            FilterChip(
                selected = f == filter,
                onClick = { search.faceFilter.value = f },
                label = { Text(f.label, fontFamily = CqMono, fontSize = 13.sp) },
                shape = RoundedCornerShape(18.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                ),
            )
        }
    }
    if (people.isEmpty()) {
        CenteredMessage("No faces found yet", "Faces appear as photos are analyzed.")
    } else {
        MediaGrid(
            items = people,
            columns = columns,
            showDateHeaders = false,
            dateFormat = com.example.galleryakio.data.DateFormatOption.FORMAT_FULL_DMY,
            selection = selection,
            onOpen = { onOpen(it, people) },
            darkMode = darkMode,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ResultsView(
    byName: List<MediaItem>,
    byContent: List<MediaItem>,
    progress: IndexProgress,
    columns: Int,
    darkMode: Boolean,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
) {
    val all = remember(byName, byContent) { byName + byContent }
    val selection = remember { SelectionState() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            "${byName.size} BY NAME  ·  ${byContent.size} BY CONTENT",
            fontFamily = CqMono, fontSize = 13.sp, color = LocalCustomColors.current.unselectedItem,
            modifier = Modifier.weight(1f),
        )
        if (progress.running) {
            Text("${(progress.fraction * 100).toInt()}% ANALYZED", fontFamily = CqMono, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (all.isEmpty()) {
        CenteredMessage("No results found", "Try a different search term")
    } else {
        MediaGrid(
            items = all,
            columns = columns,
            showDateHeaders = false,
            dateFormat = com.example.galleryakio.data.DateFormatOption.FORMAT_FULL_DMY,
            selection = selection,
            onOpen = { onOpen(it, all) },
            darkMode = darkMode,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
