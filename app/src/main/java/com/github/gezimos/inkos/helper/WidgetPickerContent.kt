package com.github.gezimos.inkos.helper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import com.github.gezimos.inkos.ui.compose.onPageKeys
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.gezimos.inkos.R
import com.github.gezimos.inkos.style.SettingsTheme
import com.github.gezimos.inkos.style.Theme
import com.github.gezimos.inkos.style.rememberScreenScale
import com.github.gezimos.inkos.style.scaled
import com.github.gezimos.inkos.ui.compose.SettingsComposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WidgetAppsLoadingContent() {
    Text(stringResource(R.string.choose_app), style = SettingsTheme.typography.header, color = Theme.colors.text)
    Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.loading_generic), style = SettingsTheme.typography.item, color = Theme.colors.text)
    }
}

/** Chrome is measured before the rows: no guessed 172dp reservation or clipped footer. */
@Composable
internal fun WidgetAppListContent(
    context: android.content.Context,
    widgetApps: List<AppWidgetHelper.WidgetApp>,
    onAppSelected: (AppWidgetHelper.WidgetApp) -> Unit,
    onClose: () -> Unit,
    textIslandsShape: Int = 0,
    onBack: (() -> Unit)? = null
) {
    val scale = rememberScreenScale()
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val apps = remember(widgetApps, query) {
        val q = query.trim()
        widgetApps.filter { it.widgets.isNotEmpty() && (q.isEmpty() || it.appLabel.contains(q, true) ||
            it.packageName.contains(q, true) || it.widgets.any { w -> w.first.contains(q, true) }) }
    }
    val latestSelection by rememberUpdatedState(onAppSelected)
    val latestBack by rememberUpdatedState(onBack ?: onClose)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = SettingsTheme.typography.item
    val textHeight = textMeasurer.measure(AnnotatedString("Ag中"), textStyle).size.height
    val rowHeightPx = maxOf(with(density) { 56.dp.scaled(scale).roundToPx() }, textHeight + with(density) { 20.dp.roundToPx() })
    val maximumHeightPx = with(density) { (LocalConfiguration.current.screenHeightDp - 72).coerceAtLeast(120).dp.roundToPx() }
    val swipeThreshold = with(density) { 32.dp.toPx() }
    val gesturePageCount = remember { java.util.concurrent.atomic.AtomicInteger(1) }
    val emptyLabel = stringResource(R.string.widget_no_matching_app)
    val title = stringResource(R.string.choose_app)
    val searchPlaceholder = stringResource(R.string.search_placeholder)
    val backLabel = stringResource(R.string.btn_back)
    val nextLabel = stringResource(R.string.home_tooltip_next)

    SubcomposeLayout(Modifier.fillMaxWidth()
        // 键盘取证：这里以前只有横拖，Page 键不认
        .onPageKeys(
            onPrevious = { page = (page - 1).coerceAtLeast(0) },
            onNext = { page = (page + 1).coerceAtMost(gesturePageCount.get() - 1) }
        )
        .pointerInput(apps.size, swipeThreshold) {
        var dragX = 0f
        detectDragGestures(
            onDragStart = { dragX = 0f },
            onDrag = { change, amount -> change.consume(); dragX += amount.x },
            onDragEnd = {
                if (dragX > swipeThreshold) page = (page - 1).coerceAtLeast(0)
                else if (dragX < -swipeThreshold) page = (page + 1).coerceAtMost(gesturePageCount.get() - 1)
            }
        )
    }) { constraints ->
        val width = constraints.maxWidth
        val maxHeight = minOf(constraints.maxHeight, maximumHeightPx).coerceAtLeast(1)
        val loose = Constraints(maxWidth = width, maxHeight = maxHeight)
        val header = subcompose("header") {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_back), backLabel,
                        Modifier.size(24.dp).clickable { latestBack() })
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = SettingsTheme.typography.header, fontWeight = FontWeight.Bold,
                        color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                com.github.gezimos.inkos.ui.compose.InkSearchField(
                    value = query,
                    onValueChange = { query = it; page = 0 },
                    placeholder = searchPlaceholder,
                    fontSize = textStyle.fontSize,
                    fontFamily = textStyle.fontFamily
                )
            }
        }.firstOrNull()?.measure(loose)
        // Measure a footer with the maximum possible number of dots, then compute final row count.
        val footerReservedPx = with(density) { 40.dp.scaled(scale).roundToPx() }
        val headerHeight = header?.height ?: 0
        val perPage = ((maxHeight - headerHeight - footerReservedPx).coerceAtLeast(rowHeightPx) / rowHeightPx).coerceIn(1, 14)
        val pageCount = ((apps.size + perPage - 1) / perPage).coerceAtLeast(1)
        gesturePageCount.set(pageCount)
        val current = page.coerceIn(0, pageCount - 1)
        val rowsHeight = minOf(perPage * rowHeightPx, (maxHeight - headerHeight - footerReservedPx).coerceAtLeast(0))
        val body = subcompose("rows") {
            if (apps.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyLabel, style = textStyle, color = Theme.colors.text.copy(alpha = 0.6f))
            } else Column(Modifier.fillMaxWidth()) {
                apps.drop(current * perPage).take(perPage).forEach { app ->
                    key(app.packageName) { WidgetAppRowAsync(context, app, rowHeightPx, textStyle) { latestSelection(app) } }
                }
            }
        }.firstOrNull()?.measure(Constraints.fixed(width, rowsHeight))
        val footer = subcompose("footer") {
            // 与多选白名单同一套：最多 8 个点 + 有页才出现的箭头（键盘弹出后页数会变多）。
            SettingsComposable.PagedNavRow(
                currentPage = current,
                pageCount = pageCount,
                onPrev = { page = (current - 1).coerceAtLeast(0) },
                onNext = { page = (current + 1).coerceAtMost(pageCount - 1) },
                modifier = Modifier.fillMaxWidth(),
                maxDots = 8,
                prevLabel = backLabel,
                nextLabel = nextLabel
            )
        }.firstOrNull()?.measure(Constraints.fixed(width, minOf(footerReservedPx, (maxHeight - headerHeight - (body?.height ?: 0)).coerceAtLeast(0))))
        // subcompose() 返回的是「该槽位这一轮真正发出来的可测量子节点」；内容一个节点都没发出来
        // 就是空列表，不是抛异常。PagedNavRow 在只有一页时直接 return（不画翻页条是设计），
        // 所以单页列表的 footer 槽位必然为空 —— 曾经对它调 .single() 直接
        // NoSuchElementException: List is empty.，小组件「选择应用」只有一页就必崩。
        // 三个槽位一律 firstOrNull() 兜底，谁哪天加个 if 提前 return 也不会再炸。
        val bodyHeight = body?.height ?: 0
        val footerHeight = footer?.height ?: 0
        layout(width, headerHeight + bodyHeight + footerHeight) {
            header?.placeRelative(0, 0)
            body?.placeRelative(0, headerHeight)
            footer?.placeRelative(0, headerHeight + bodyHeight)
        }
    }
}

@Composable
private fun WidgetAppRowAsync(context: android.content.Context, app: AppWidgetHelper.WidgetApp,
    heightPx: Int, style: androidx.compose.ui.text.TextStyle, onSelect: () -> Unit) {
    var image by remember(app.packageName) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        image = withContext(Dispatchers.IO) {
            try { drawableToBitmap(context.packageManager.getApplicationIcon(app.packageName)).asImageBitmap() } catch (_: Exception) { null }
        }
    }
    val height = with(LocalDensity.current) { heightPx.toDp() }
    Row(Modifier.fillMaxWidth().height(height).clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { image?.let { Image(it, null, Modifier.fillMaxSize()) } }
        Spacer(Modifier.width(12.dp))
        Text(app.appLabel, style = style, color = Theme.colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${app.widgets.size}", style = style, color = Theme.colors.text.copy(alpha = 0.5f))
    }
}

@Composable
internal fun WidgetListContent(app: AppWidgetHelper.WidgetApp, appWidgetHelper: AppWidgetHelper,
    onBack: () -> Unit, onWidgetPicked: (android.appwidget.AppWidgetProviderInfo) -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 160).coerceAtLeast(120).dp
    Box(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Image(painterResource(R.drawable.ic_back), stringResource(R.string.btn_back),
                    Modifier.offset(x = (-3).dp).size(24.dp).clickable(onClick = onBack))
                Text(app.appLabel.uppercase(), style = SettingsTheme.typography.item, fontWeight = FontWeight.Bold,
                    color = Theme.colors.text, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(app.widgets, key = { it.second.provider.flattenToString() }) { (label, provider) ->
                    var preview by remember(provider.provider) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    LaunchedEffect(provider.provider) {
                        // Resource I/O and bitmap conversion are not composition work.
                        preview = withContext(Dispatchers.IO) {
                            appWidgetHelper.loadWidgetPreview(provider)?.let { drawableToBitmap(it).asImageBitmap() }
                        }
                    }
                    Column(Modifier.fillMaxWidth().clickable { onWidgetPicked(provider) }.padding(vertical = 8.dp)) {
                        Text(label, style = SettingsTheme.typography.header, color = Theme.colors.text, fontWeight = FontWeight.Medium)
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            preview?.let { Image(it, label, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
                        }
                        Text("${provider.minWidth}×${provider.minHeight} dp", fontSize = 11.sp, color = Theme.colors.text.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
