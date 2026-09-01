package com.schmmd.drumroll.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.data.UsagePoint
import com.schmmd.drumroll.data.UsageSeries
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gallons per day over time.
 *
 * One measure, one series, so there is no legend to read — the title names it — and no
 * second axis. The y-scale starts at zero because the quantity is a magnitude: a draw
 * that doubled should look twice as tall, which a truncated axis would hide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageChartScreen(readings: List<Reading>, onBack: () -> Unit) {
    val points = remember(readings) { UsageSeries.from(readings) }
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Water use") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (points.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "Two readings on different days are needed before there is a " +
                            "rate to draw.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Spacer(Modifier.height(4.dp))
            Text("Gallons per day", style = MaterialTheme.typography.titleMedium)

            // The reading a tap landed on, or the most recent when nothing is selected.
            val shown = points[selected ?: points.lastIndex]
            Text(
                buildString {
                    append("${shown.gallonsPerDay.roundToInt()} gal/day")
                    append(" · ${shown.date}")
                    append(" · over ${shown.spanDays} day${if (shown.spanDays == 1L) "" else "s"}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            UsagePlot(
                points = points,
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )

            Text(
                if (points.size == 1) {
                    "One interval so far. Each point is the average since the reading before it."
                } else {
                    "Tap a point to inspect it. Each point is the average since the " +
                        "reading before it, so a long gap is a coarser average."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsagePlot(
    points: List<UsagePoint>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val series = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface

    // Recessive: the data should be the darkest thing in the frame.
    val labelStyle = TextStyle(fontSize = 11.sp, color = ink, fontFamily = FontFamily.Monospace)

    val first = points.first().date
    val last = points.last().date
    val span = maxOf(1L, last.toEpochDay() - first.toEpochDay())

    // A meter cannot run backwards, but the log can: a reading entered for an earlier
    // date with a higher register, or a misread digit, both show up as a negative rate.
    // Drawing them below a zero line is how they get noticed; clamping the axis at zero
    // would hide the very rows worth checking.
    val values = points.map { it.gallonsPerDay }
    val scale = remember(values) { niceScale(minOf(0.0, values.min()), maxOf(0.0, values.max())) }

    // Positions are recomputed inside the tap handler from the same geometry, so the
    // hit target follows the marker rather than an index guess.
    var geometry by remember { mutableStateOf<PlotGeometry?>(null) }

    Canvas(
        modifier.pointerInput(points, geometry) {
            detectTapGestures { tap ->
                val g = geometry ?: return@detectTapGestures
                val nearest = points.indices.minByOrNull { i ->
                    abs(g.x(points[i]) - tap.x)
                } ?: return@detectTapGestures
                // Generous target: a fingertip is far wider than an 8dp dot.
                onSelect(if (abs(g.x(points[nearest]) - tap.x) < 48.dp.toPx()) nearest else null)
            }
        },
    ) {
        val leftGutter = 44.dp.toPx()
        val bottomGutter = 22.dp.toPx()
        val plotWidth = size.width - leftGutter
        val plotHeight = size.height - bottomGutter
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        val g = PlotGeometry(
            left = leftGutter,
            height = plotHeight,
            width = plotWidth,
            firstDay = first.toEpochDay(),
            span = span,
            lo = scale.lo,
            hi = scale.hi,
            single = points.size == 1,
        )
        geometry = g

        // Gridlines and their labels, drawn first so the series sits on top.
        var tick = scale.lo
        while (tick <= scale.hi + scale.step / 1000) {
            val y = g.y(tick)
            val isZero = kotlin.math.abs(tick) < scale.step / 1000
            drawLine(
                color = grid.copy(alpha = if (isZero) 0.9f else 0.45f),
                start = Offset(leftGutter, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
            val label = measurer.measure(axisLabel(tick), labelStyle)
            drawText(
                label,
                topLeft = Offset(
                    leftGutter - label.size.width - 6.dp.toPx(),
                    y - label.size.height / 2f,
                ),
            )
            tick += scale.step
        }

        val dateFormat = DateTimeFormatter.ofPattern("d MMM")
        val firstLabel = measurer.measure(first.format(dateFormat), labelStyle)
        drawText(firstLabel, topLeft = Offset(leftGutter, plotHeight + 4.dp.toPx()))
        if (points.size > 1) {
            val lastLabel = measurer.measure(last.format(dateFormat), labelStyle)
            drawText(
                lastLabel,
                topLeft = Offset(size.width - lastLabel.size.width, plotHeight + 4.dp.toPx()),
            )
        }

        // The line, only where there is more than one point to join.
        if (points.size > 1) {
            val path = Path().apply {
                points.forEachIndexed { i, p ->
                    val x = g.x(p)
                    val y = g.y(p.gallonsPerDay)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(path, color = series, style = Stroke(width = 2.dp.toPx()))
        }

        points.forEachIndexed { i, p ->
            val centre = Offset(g.x(p), g.y(p.gallonsPerDay))
            val isSelected = i == (selected ?: points.lastIndex)
            // A surface ring keeps overlapping markers legible where readings cluster.
            drawCircle(surface, radius = (if (isSelected) 7 else 5).dp.toPx(), center = centre)
            drawCircle(series, radius = (if (isSelected) 5 else 4).dp.toPx(), center = centre)
            if (isSelected) {
                drawLine(
                    color = series.copy(alpha = 0.35f),
                    start = Offset(centre.x, centre.y),
                    end = Offset(centre.x, g.y(0.0)),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

/** Maps a point to pixels. Held so the tap handler and the painter agree exactly. */
private data class PlotGeometry(
    val left: Float,
    val width: Float,
    val height: Float,
    val firstDay: Long,
    val span: Long,
    val lo: Double,
    val hi: Double,
    val single: Boolean,
) {
    /** Time-proportional, not evenly spaced: a six-month gap must look like one. */
    fun x(point: UsagePoint): Float = when {
        // A lone point has no span to place it along, and pinned to the left edge it
        // reads as the start of a trend that does not exist yet.
        single -> left + width / 2f
        else -> left + width * ((point.date.toEpochDay() - firstDay).toFloat() / span)
    }

    fun y(value: Double): Float =
        (height * (1.0 - (value - lo) / (hi - lo))).toFloat()
}

private data class Scale(val lo: Double, val hi: Double, val step: Double)

/**
 * An axis a person would have drawn: round steps, and both ends landing on one.
 * [lo] and [hi] are widened, never narrowed, so no point falls outside the plot.
 */
private fun niceScale(lo: Double, hi: Double, ticks: Int = 4): Scale {
    if (hi <= lo) return Scale(lo, lo + 1.0, 1.0)
    val step = niceStep((hi - lo) / ticks)
    val flooredLo = kotlin.math.floor(lo / step) * step
    val ceilingHi = kotlin.math.ceil(hi / step) * step
    return Scale(flooredLo, if (ceilingHi > flooredLo) ceilingHi else flooredLo + step, step)
}

/** Rounds a step up to 1, 2, 2.5 or 5 times a power of ten. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { raw <= it * magnitude } * magnitude
}

/** Thousands get a k, so the gutter stays narrow and the labels stay readable. */
private fun axisLabel(value: Double): String {
    val rounded = value.roundToInt()
    return when {
        kotlin.math.abs(rounded) >= 10_000 -> "${rounded / 1000}k"
        else -> rounded.toString()
    }
}
