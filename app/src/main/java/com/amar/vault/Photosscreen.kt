package com.amar.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.ChevronGray
import com.amar.vault.ui.theme.IconBgLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════
// PhotosScreen — Apple-level gallery
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun PhotosScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val stashResults by viewModel.results.collectAsStateWithLifecycle()
    val results = remember(stashResults) { stashResults.map { it.toVaultItem() } }
    val allItems by viewModel.allItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isSearchLoading.collectAsStateWithLifecycle()

    val photos = remember(allItems) {
        allItems.filter { it.itemType !in setOf("pdf", "word", "excel", "epub") }
            .sortedByDescending { it.timestamp }
    }

    val moments = remember(photos) { groupByDate(photos) }
    val people = remember(photos) { extractPeople(photos) }
    val suggestions = remember(photos) { buildSuggestions(photos) }
    val memories = remember(photos) { getMemories(photos) }

    var viewerPhoto by remember { mutableStateOf<VaultItem?>(null) }
    var viewerList by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var isSearchActive by remember { mutableStateOf(false) }

    // Staggered entrance animation
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(50); appeared = true }

    Box(modifier = Modifier.fillMaxSize().background(Cream)) {
        when {
            viewerPhoto != null -> {
                PhotoViewer(
                    photo = viewerPhoto!!,
                    photos = viewerList.ifEmpty { photos },
                    onClose = { viewerPhoto = null },
                )
            }
            isSearchActive -> {
                SearchMode(
                    query = query,
                    onQueryChange = { viewModel.updateQuery(it) },
                    results = results.filter { it.itemType !in setOf("pdf", "word", "excel", "epub") },
                    isLoading = isLoading,
                    suggestions = suggestions,
                    people = people,
                    onCancel = { isSearchActive = false; viewModel.updateQuery("") },
                    onPhotoClick = { photo, list -> viewerPhoto = photo; viewerList = list },
                )
            }
            else -> {
                MainGallery(
                    photos = photos,
                    moments = moments,
                    people = people,
                    memories = memories,
                    appeared = appeared,
                    onSearchClick = { isSearchActive = true },
                    onBack = onBack,
                    onPhotoClick = { photo, list -> viewerPhoto = photo; viewerList = list },
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Main Gallery with staggered animations
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun MainGallery(
    photos: List<VaultItem>,
    moments: List<DateGroup>,
    people: List<PersonInfo>,
    memories: List<MemoryItem>,
    appeared: Boolean,
    onSearchClick: () -> Unit,
    onBack: () -> Unit,
    onPhotoClick: (VaultItem, List<VaultItem>) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        // ── Header with stagger ─────────────────────────────────
        item(span = { GridItemSpan(3) }) {
            StaggerIn(appeared, 0) {
                Column(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 20.dp)) {
                    TextButton(onClick = onBack) {
                        Text("← Home", color = WarmBrown, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text("Photos", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft, letterSpacing = (-0.8).sp)
                        Text("${photos.size}", fontSize = 14.sp, color = WarmBrown, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ── Search bar ──────────────────────────────────────────
        item(span = { GridItemSpan(3) }) {
            StaggerIn(appeared, 1) {
                Surface(
                    onClick = onSearchClick,
                    shape = RoundedCornerShape(14.dp),
                    color = CreamLight,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔍", fontSize = 15.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("Search photos, people, places…", fontSize = 16.sp, color = ChevronGray)
                    }
                }
            }
        }

        // ── Memory Resurfacing — On This Day ────────────────────
        if (memories.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) {
                StaggerIn(appeared, 2) {
                    Column(modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(end = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("On This Day", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft, letterSpacing = (-0.3).sp)
                            Text("✨ Resurfaced", fontSize = 12.sp, color = WarmBrown, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(14.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(end = 24.dp),
                        ) {
                            itemsIndexed(memories) { i, mem ->
                                MemoryCard(mem, i) { onPhotoClick(mem.photo, photos) }
                            }
                        }
                    }
                }
            }
        }

        // ── Moments — horizontal scroll cards ───────────────────
        if (moments.size >= 2) {
            item(span = { GridItemSpan(3) }) {
                StaggerIn(appeared, 3) {
                    Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(end = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Moments", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft, letterSpacing = (-0.3).sp)
                            Text("See All", fontSize = 13.sp, color = WarmBrownDark, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(14.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 24.dp),
                        ) {
                            itemsIndexed(moments.take(6)) { i, moment ->
                                MomentCard(moment, i) { onPhotoClick(moment.photos.first(), moment.photos) }
                            }
                        }
                    }
                }
            }
        }

        // ── People ──────────────────────────────────────────────
        if (people.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) {
                StaggerIn(appeared, 4) {
                    Column(modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 4.dp)) {
                        Text("People", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft, letterSpacing = (-0.3).sp)
                        Spacer(Modifier.height(16.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            contentPadding = PaddingValues(end = 24.dp),
                        ) {
                            itemsIndexed(people) { i, person ->
                                PersonBubble(person, i) {
                                    if (person.photos.isNotEmpty()) onPhotoClick(person.photos.first(), person.photos)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Highlights grid header ──────────────────────────────
        item(span = { GridItemSpan(3) }) {
            StaggerIn(appeared, 5) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("All Photos", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft, letterSpacing = (-0.3).sp)
                    Text("${photos.size}", fontSize = 13.sp, color = WarmBrown)
                }
            }
        }

        // ── Photo grid ──────────────────────────────────────────
        itemsIndexed(photos, key = { _, item -> item.id }) { index, photo ->
            PhotoTile(photo, index, appeared) { onPhotoClick(photo, photos) }
        }

        // ── Empty state ─────────────────────────────────────────
        if (photos.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                Column(Modifier.fillMaxWidth().padding(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📷", fontSize = 52.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Your gallery is empty", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft)
                    Text("Screenshots will appear here", fontSize = 14.sp, color = WarmBrown, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Staggered entrance animation wrapper
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun StaggerIn(visible: Boolean, index: Int, content: @Composable () -> Unit) {
    val delayMs = 60 * index
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) { delay(delayMs.toLong()); show = true } }

    val alpha by animateFloatAsState(if (show) 1f else 0f, tween(450), label = "staggerAlpha$index")
    val offsetY by animateDpAsState(if (show) 0.dp else 24.dp, spring(stiffness = Spring.StiffnessLow), label = "staggerY$index")

    Box(modifier = Modifier.alpha(alpha).offset(y = offsetY)) { content() }
}

// ═══════════════════════════════════════════════════════════════════════
// Memory Card — On This Day
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun MemoryCard(mem: MemoryItem, index: Int, onClick: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(index * 80L + 200); show = true }
    val s by animateFloatAsState(if (show) 1f else 0.85f, spring(stiffness = Spring.StiffnessLow), label = "memScale$index")
    val a by animateFloatAsState(if (show) 1f else 0f, tween(400), label = "memAlpha$index")

    Box(
        modifier = Modifier
            .scale(s).alpha(a)
            .width(140.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.aspectRatio(0.75f)) {
            VaultImage(mem.photo, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)), startY = 150f)))
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(mem.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f), letterSpacing = 0.5.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Moment Card — date-grouped horizontal scroll
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun MomentCard(moment: DateGroup, index: Int, onClick: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(index * 70L + 150); show = true }
    val s by animateFloatAsState(if (show) 1f else 0.9f, spring(stiffness = Spring.StiffnessLow), label = "momScale$index")
    val a by animateFloatAsState(if (show) 1f else 0f, tween(450), label = "momAlpha$index")

    Box(
        modifier = Modifier
            .scale(s).alpha(a)
            .width(220.dp).height(290.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        if (moment.photos.isNotEmpty()) {
            VaultImage(moment.photos.first(), Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)), startY = 180f)))
        // Top shine
        Box(Modifier.fillMaxWidth().height(100.dp).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(moment.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), letterSpacing = 1.sp)
            Text("${moment.photos.size} photos", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = (-0.2).sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Person Bubble with pop-in animation
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun PersonBubble(person: PersonInfo, index: Int, onClick: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(index * 60L + 300); show = true }
    val s by animateFloatAsState(if (show) 1f else 0.7f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "pScale$index")
    val a by animateFloatAsState(if (show) 1f else 0f, tween(350), label = "pAlpha$index")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.scale(s).alpha(a).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(CreamLight),
            contentAlignment = Alignment.Center,
        ) {
            if (person.photos.isNotEmpty()) {
                VaultImage(person.photos.first(), Modifier.fillMaxSize())
            } else {
                Text(person.name.take(1).uppercase(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = WarmBrown)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(person.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft)
        Text("${person.photos.size}", fontSize = 11.sp, color = WarmBrown)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Photo tile with scale-in animation
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun PhotoTile(photo: VaultItem, index: Int, appeared: Boolean, onClick: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(appeared) { if (appeared) { delay((index * 25L + 400).coerceAtMost(1200)); show = true } }
    val s by animateFloatAsState(if (show) 1f else 0.88f, spring(stiffness = Spring.StiffnessLow), label = "tileS$index")
    val a by animateFloatAsState(if (show) 1f else 0f, tween(300), label = "tileA$index")

    Box(
        modifier = Modifier
            .scale(s).alpha(a)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
    ) {
        VaultImage(photo, Modifier.fillMaxSize())
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Search mode
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SearchMode(
    query: String, onQueryChange: (String) -> Unit,
    results: List<VaultItem>, isLoading: Boolean,
    suggestions: List<String>, people: List<PersonInfo>,
    onCancel: () -> Unit, onPhotoClick: (VaultItem, List<VaultItem>) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = onQueryChange,
                placeholder = { Text("Search photos…", color = ChevronGray) },
                modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CreamLight, unfocusedContainerColor = CreamLight, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                trailingIcon = { if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onCancel) { Text("Cancel", color = WarmBrownDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(16.dp))

        if (query.isBlank()) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("SUGGESTED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarmBrown, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(12.dp))
                suggestions.forEach { s ->
                    Surface(onClick = { onQueryChange(s) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(CreamLight), contentAlignment = Alignment.Center) {
                                Text("🔍", fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(s, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = CharcoalSoft)
                        }
                    }
                }
                if (people.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text("PEOPLE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarmBrown, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(14.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        itemsIndexed(people) { i, p -> PersonBubble(p, i) { onQueryChange(p.name) } }
                    }
                }
            }
        } else if (results.isEmpty() && !isLoading) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("🔍", fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("No results", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft)
                Text("Try different keywords", fontSize = 14.sp, color = WarmBrown)
            }
        } else {
            // Top match hero
            if (results.isNotEmpty()) {
                Surface(onClick = { onPhotoClick(results.first(), results) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Box(Modifier.aspectRatio(16f / 10f)) {
                        VaultImage(results.first(), Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), startY = 200f)))
                        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                            Text("TOP MATCH", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), letterSpacing = 1.5.sp)
                            Text(results.first().ocrText.substringBefore("\n[").take(40), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (results.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text("${results.size} RESULTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = WarmBrown, letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(results.drop(1), key = { it.id }) { photo ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).clickable { onPhotoClick(photo, results) }) {
                            VaultImage(photo, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Photo Viewer — blur bg, swipe-to-dismiss, horizontal pager, page dots
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun PhotoViewer(photo: VaultItem, photos: List<VaultItem>, onClose: () -> Unit) {
    var currentIndex by remember(photo) {
        mutableIntStateOf(photos.indexOfFirst { it.id == photo.id }.coerceAtLeast(0))
    }
    val current = photos.getOrNull(currentIndex) ?: photo
    var showMeta by remember { mutableStateOf(true) }

    // Drag state for swipe-to-dismiss
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var zoomOffsetX by remember { mutableFloatStateOf(0f) }
    var zoomOffsetY by remember { mutableFloatStateOf(0f) }
    val dismissProgress = (abs(dragY) / 400f).coerceIn(0f, 1f)

    // Animated background opacity
    val bgAlpha by animateFloatAsState(1f - dismissProgress * 0.7f, tween(if (isDragging) 0 else 250), label = "bgA")
    val photoScale by animateFloatAsState(1f - dismissProgress * 0.15f, tween(if (isDragging) 0 else 250), label = "phS")

    // Entrance animation
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(30); entered = true }
    LaunchedEffect(current.id) {
        zoomScale = 1f
        zoomOffsetX = 0f
        zoomOffsetY = 0f
        dragX = 0f
        dragY = 0f
    }
    val enterAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(300), label = "enterA")
    val enterScale by animateFloatAsState(if (entered) 1f else 0.92f, spring(stiffness = Spring.StiffnessLow), label = "enterS")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(enterAlpha)
            .scale(enterScale)
    ) {
        // ── Blurred background ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(bgAlpha)
        ) {
            VaultImage(current, Modifier.fillMaxSize().blur(40.dp).graphicsLayer { scaleX = 1.4f; scaleY = 1.4f })
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }

        // ── Photo with drag gestures ────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentIndex) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f || zoomScale > 1f) {
                            zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                            if (zoomScale > 1f) {
                                zoomOffsetX += pan.x
                                zoomOffsetY += pan.y
                            } else {
                                zoomOffsetX = 0f
                                zoomOffsetY = 0f
                            }
                        }
                    }
                }
                .pointerInput(currentIndex) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                            if (zoomScale > 1f) { dragY = 0f; dragX = 0f; return@detectDragGestures }
                            // Swipe to dismiss
                            if (abs(dragY) > 120) { onClose(); return@detectDragGestures }
                            // Horizontal swipe
                            if (dragX < -80 && currentIndex < photos.size - 1) currentIndex++
                            else if (dragX > 80 && currentIndex > 0) currentIndex--
                            dragY = 0f; dragX = 0f
                        },
                        onDragCancel = { isDragging = false; dragY = 0f; dragX = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (zoomScale > 1f) {
                                zoomOffsetX += dragAmount.x
                                zoomOffsetY += dragAmount.y
                            } else {
                                dragY += dragAmount.y
                                dragX += dragAmount.x
                            }
                        }
                    )
                }
                .pointerInput(currentIndex) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (zoomScale > 1f) {
                                zoomScale = 1f
                                zoomOffsetX = 0f
                                zoomOffsetY = 0f
                            } else {
                                zoomScale = 2.5f
                                showMeta = false
                            }
                        },
                        onTap = { showMeta = !showMeta },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            VaultImage(
                item = current,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = dragX + zoomOffsetX
                        translationY = dragY + zoomOffsetY
                        scaleX = photoScale * zoomScale
                        scaleY = photoScale * zoomScale
                        rotationZ = (dragX * 0.015f).coerceIn(-4f, 4f)
                    },
                contentScale = ContentScale.Fit,
            )
        }

        // ── Top bar ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = showMeta && !isDragging,
            enter = fadeIn(tween(200)) + slideInVertically { -it },
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("←", color = Color.White, fontSize = 22.sp) }
                Text("${currentIndex + 1} of ${photos.size}", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(44.dp))
            }
        }

        // ── Bottom metadata ─────────────────────────────────────
        AnimatedVisibility(
            visible = showMeta && !isDragging,
            enter = fadeIn(tween(250)) + slideInVertically { it },
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                    .padding(20.dp).padding(bottom = 8.dp)
            ) {
                Text(formatTs(current.timestamp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                val preview = current.ocrText.substringBefore("\n[").trim().take(120)
                if (preview.isNotBlank()) {
                    Text(preview, fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // ── Page dots ───────────────────────────────────────────
        if (photos.size in 2..19 && showMeta) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (showMeta) 95.dp else 20.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                photos.forEachIndexed { i, _ ->
                    val dotSize by animateDpAsState(if (i == currentIndex) 7.dp else 5.dp, spring(stiffness = Spring.StiffnessMedium), label = "dot$i")
                    Box(Modifier.size(dotSize).clip(CircleShape).background(if (i == currentIndex) Color.White else Color.White.copy(alpha = 0.3f)))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Shared image component
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun VaultImage(item: VaultItem, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(item.uri).allowHardware(true).crossfade(300).memoryCachePolicy(CachePolicy.ENABLED).build(),
        contentDescription = null, contentScale = contentScale, modifier = modifier,
    )
}

// ═══════════════════════════════════════════════════════════════════════
// Data models + intelligence
// ═══════════════════════════════════════════════════════════════════════

private data class DateGroup(val label: String, val photos: List<VaultItem>)
data class PersonInfo(val name: String, val photos: List<VaultItem>)
data class MemoryItem(val photo: VaultItem, val label: String, val priority: Int)

private fun groupByDate(photos: List<VaultItem>): List<DateGroup> {
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val fmt = SimpleDateFormat("MMMM d", Locale.getDefault())

    return photos.groupBy { item ->
        val cal = Calendar.getInstance().apply { timeInMillis = item.timestamp }
        when {
            sameDay(cal, today) -> "Today"
            sameDay(cal, yesterday) -> "Yesterday"
            else -> fmt.format(Date(item.timestamp))
        }
    }.map { (label, items) -> DateGroup(label, items) }
}

private fun getMemories(photos: List<VaultItem>): List<MemoryItem> {
    val now = Calendar.getInstance()
    val memories = mutableListOf<MemoryItem>()

    photos.forEach { photo ->
        val cal = Calendar.getInstance().apply { timeInMillis = photo.timestamp }
        val daysAgo = ((now.timeInMillis - photo.timestamp) / 86400000).toInt()

        // On This Day (same month+day, different year)
        if (cal.get(Calendar.MONTH) == now.get(Calendar.MONTH) && cal.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH) && cal.get(Calendar.YEAR) < now.get(Calendar.YEAR)) {
            val years = now.get(Calendar.YEAR) - cal.get(Calendar.YEAR)
            memories.add(MemoryItem(photo, "$years year${if (years > 1) "s" else ""} ago today", 10))
        }
        // Recent highlights
        if (daysAgo in 1..14) {
            memories.add(MemoryItem(photo, if (daysAgo == 1) "Yesterday" else "$daysAgo days ago", 3))
        }
    }

    return memories.sortedByDescending { it.priority }.take(6)
}

private fun extractPeople(photos: List<VaultItem>): List<PersonInfo> {
    val nameRe = Regex("[A-Z][a-z]{2,15}")
    val stop = setOf("The","This","That","They","There","Their","These","Those","From","With","Have","Has","Had","Was","Were","Are","Been","Being","Would","Could","Should","Will","Shall","Can","May","Must","Just","More","Most","Some","Any","All","Each","Every","Other","New","Old","Good","Great","Best","Last","Next","First","Full","Total","Amount","Mobile","Search","Reply","Share","Open","Close","Today","View","Page","Settings","Download","Upload","Delete","Edit","Save","Home","Back","Menu","Help","Info","Data","File","Get","Scan","Pay","App","Accepted","Here","Using")
    val names = mutableMapOf<String, MutableList<VaultItem>>()
    photos.forEach { p ->
        nameRe.findAll(p.ocrText.substringBefore("\n[")).map { it.value }.filter { it !in stop }.distinct().forEach { n -> names.getOrPut(n) { mutableListOf() }.add(p) }
    }
    return names.filter { it.value.size >= 2 }.map { (n, ps) -> PersonInfo(n, ps) }.sortedByDescending { it.photos.size }.take(8)
}

private fun buildSuggestions(photos: List<VaultItem>): List<String> {
    val s = mutableListOf<String>()
    if (photos.any { it.ocrText.lowercase().let { t -> t.contains("upi") || t.contains("pay") } }) s.add("Payments & UPI")
    if (photos.any { it.ocrText.lowercase().contains("otp") }) s.add("OTP codes")
    if (photos.any { "\\d{10}".toRegex().containsMatchIn(it.ocrText) }) s.add("Phone numbers")
    if (photos.any { it.ocrText.lowercase().contains("http") }) s.add("Links & URLs")
    s.add("Recent screenshots")
    s.add("Today")
    return s.take(6)
}

private fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
private fun formatTs(ts: Long) = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(ts))
