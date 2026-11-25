package br.com.cafeanalise.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.cafeanalise.data.Market
import br.com.cafeanalise.util.fmt

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Gold, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = TextMain, bold: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
        Text(
            value, style = MaterialTheme.typography.bodyMedium, color = valueColor,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = Muted)
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Gold,
                    selectedLabelColor = Color.Black,
                    labelColor = TextMain,
                ),
            )
        }
    }
}

@Composable
fun MarketSelector(selected: Market, onSelect: (Market) -> Unit) =
    ChipRow(Market.entries.toList(), selected, { if (it == Market.ARABICA) "Arábica (NY)" else "Robusta (Londres)" }, onSelect)

@Composable
fun Disclaimer(text: String = "Cotações com atraso (~15 min). Informações para apoio à decisão — não são recomendação de compra ou venda.") {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
}

// ---------------------------------------------------------------- gráficos

data class ChartSeries(
    val values: List<Double?>,
    val color: Color,
    val width: Float = 2f,
    val dashed: Boolean = false,
    /** Cor de cada ponto (quando os pontos são desenhados); null = cor da linha. */
    val pointColors: List<Color>? = null,
)

data class ChartBand(val lower: List<Double?>, val upper: List<Double?>, val color: Color)

/**
 * Gráfico de linhas simples desenhado em Canvas (sem bibliotecas externas).
 * [xLabels]: pares (índice, rótulo) exibidos no eixo X.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    bands: List<ChartBand> = emptyList(),
    xLabels: List<Pair<Int, String>> = emptyList(),
    decimals: Int = 2,
    showDots: Boolean = false,
) {
    Canvas(modifier) {
        val all = series.flatMap { it.values.filterNotNull() } +
            bands.flatMap { (it.lower + it.upper).filterNotNull() }
        if (all.isEmpty()) return@Canvas
        var minV = all.min()
        var maxV = all.max()
        if (maxV - minV < 1e-9) { maxV += 1.0; minV -= 1.0 }
        val pad = (maxV - minV) * 0.08
        minV -= pad; maxV += pad

        val n = maxOf(series.maxOfOrNull { it.values.size } ?: 0, bands.maxOfOrNull { it.upper.size } ?: 0)
        if (n == 0) return@Canvas
        val rightPad = 52.dp.toPx()
        val topPad = 6.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val w = size.width - rightPad
        val h = size.height - topPad - bottomPad

        fun x(i: Int): Float = if (n <= 1) w / 2 else i * w / (n - 1)
        fun y(v: Double): Float = topPad + ((maxV - v) / (maxV - minV) * h).toFloat()

        val paint = android.graphics.Paint().apply {
            color = Muted.toArgb()
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }

        // grade + eixo Y
        for (k in 0..4) {
            val v = minV + (maxV - minV) * k / 4
            val yy = y(v)
            drawLine(Color(0x1FFFFFFF), Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(v.fmt(decimals), w + 6.dp.toPx(), yy + 4.dp.toPx(), paint)
        }

        // eixo X
        xLabels.forEach { (i, label) ->
            val tw = paint.measureText(label)
            val xx = (x(i) - tw / 2).coerceIn(0f, maxOf(0f, w - tw))
            drawContext.canvas.nativeCanvas.drawText(label, xx, size.height - 4.dp.toPx(), paint)
        }

        // faixas
        bands.forEach { b ->
            val idx = b.upper.indices.filter { b.upper[it] != null && b.lower.getOrNull(it) != null }
            if (idx.size >= 2) {
                val p = Path()
                idx.forEachIndexed { k, i -> if (k == 0) p.moveTo(x(i), y(b.upper[i]!!)) else p.lineTo(x(i), y(b.upper[i]!!)) }
                idx.reversed().forEach { i -> p.lineTo(x(i), y(b.lower[i]!!)) }
                p.close()
                drawPath(p, b.color)
            }
        }

        // linhas
        series.forEach { s ->
            val p = Path()
            var started = false
            s.values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                if (!started) { p.moveTo(x(i), y(v)); started = true } else p.lineTo(x(i), y(v))
            }
            drawPath(
                p, s.color,
                style = Stroke(
                    width = s.width.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
                ),
            )
            if (showDots && !s.dashed) s.values.forEachIndexed { i, v ->
                if (v != null) drawCircle(s.pointColors?.getOrNull(i) ?: s.color, radius = 4.dp.toPx(), center = Offset(x(i), y(v)))
            }
        }
    }
}
