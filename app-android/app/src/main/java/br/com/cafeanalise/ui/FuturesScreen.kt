package br.com.cafeanalise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.cafeanalise.UiState
import br.com.cafeanalise.analysis.Analytics
import br.com.cafeanalise.data.CccvProduct
import br.com.cafeanalise.data.CurvePoint
import br.com.cafeanalise.data.Market
import br.com.cafeanalise.data.Settings
import br.com.cafeanalise.util.brl
import br.com.cafeanalise.util.fmt
import br.com.cafeanalise.util.label
import br.com.cafeanalise.util.pct

/** Produto do CCCV usado como referência de cada bolsa. */
fun cccvProductFor(m: Market) = if (m == Market.ARABICA) CccvProduct.DURA else CccvProduct.CONILON

/**
 * Diferencial atual do CCCV em relação à bolsa (R$/saca): preço do CCCV − equivalente da bolsa em reais.
 * Usa o contrato contínuo (o mais negociado) como referência.
 */
fun cccvBasis(state: UiState, m: Market): Double? {
    val day = state.cccv?.getOrNull()?.last ?: return null
    val cccvPrice = cccvProductFor(m).price(day) ?: return null
    val q = state.quote(m) ?: return null
    val fx = state.usdBrl ?: return null
    return cccvPrice - m.usdPerBag(q.price) * fx
}

/** Preço estimado no CCCV para um preço de bolsa, mantidos o dólar e o diferencial de hoje. */
fun cccvEstimate(state: UiState, m: Market, bolsaPrice: Double): Double? {
    val fx = state.usdBrl ?: return null
    val basis = cccvBasis(state, m) ?: return null
    return m.usdPerBag(bolsaPrice) * fx + basis
}

@Composable
fun FuturesScreen(state: UiState, settings: Settings) {
    var m by rememberSaveable { mutableStateOf(Market.ROBUSTA) }
    val curve = state.curve(m)
    val product = cccvProductFor(m)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text("Mercado futuro", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Preço de cada vencimento negociado hoje. É o que compradores e vendedores aceitam pagar agora para entrega em cada mês.",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
        }
        MarketSelector(m) { m = it }

        when {
            state.curveLoading && curve.isEmpty() -> SectionCard { Text("Carregando vencimentos…", color = Muted) }
            curve.isEmpty() -> SectionCard { Text("Não foi possível carregar a curva de vencimentos.", color = Down) }
            else -> {
                val tvf = todayVsFuture(state, m)
                if (tvf != null) SectionCard {
                    SectionTitle(
                        "Hoje × futuro (${tvf.unit})",
                        if (tvf.inCccv) "${product.title} no CCCV: preço de hoje e estimativa para cada vencimento"
                        else (if (m == Market.ARABICA) "ICE Nova York" else "ICE Londres"),
                    )
                    TodayVsFutureChart(tvf)
                    val (txt, b) = readingVsToday(tvf)
                    Text(
                        txt, color = biasColor(b), fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp),
                    )
                }

                if (tvf != null && tvf.inCccv) WaitCard(tvf, settings, product.title)

                SectionCard {
                    val basis = cccvBasis(state, m)
                    SectionTitle(
                        "Vencimentos",
                        if (basis != null) "${product.title} no CCCV estimado = bolsa em R$ + diferencial de hoje " +
                            "(${if (basis >= 0) "+" else "−"}${brl(kotlin.math.abs(basis))})"
                        else "Estimativa do CCCV indisponível (falta CCCV, bolsa ou dólar)",
                    )
                    FutureTable(state, m, showBolsa = true)
                }
            }
        }

        SectionCard {
            SectionTitle("Como ler")
            Text(
                "• Vencimentos abaixo do preço de hoje: o mercado está pagando menos para entregar mais tarde, ou seja, espera preço menor à frente.\n" +
                    "• Vencimentos acima do preço de hoje: o mercado paga mais para entregar mais tarde, ou seja, espera preço maior à frente.\n" +
                    "• O CCCV estimado supõe que o dólar e o diferencial de Vitória fiquem como hoje.",
                style = MaterialTheme.typography.bodySmall, color = TextMain,
            )
        }
        Disclaimer()
    }
}

/**
 * Comparação "hoje × futuro": o primeiro ponto é o preço de hoje e os demais são os vencimentos,
 * todos na mesma unidade. Quando há CCCV, dólar e bolsa, a unidade é R$/saca no CCCV
 * (o preço onde a família vende); senão, a unidade da própria bolsa.
 */
data class TodayVsFuture(
    val today: Double,
    val points: List<Pair<CurvePoint, Double>>,
    val inCccv: Boolean,
    val unit: String,
    val decimals: Int,
) {
    fun fmt(v: Double) = if (inCccv) brl(v) else v.fmt(decimals) + " " + unit
    fun pctVsToday(v: Double) = Analytics.pctDiff(v, today)
}

fun todayVsFuture(state: UiState, m: Market): TodayVsFuture? {
    val curve = state.curve(m)
    if (curve.isEmpty()) return null
    val cccvToday = state.cccv?.getOrNull()?.last?.let { cccvProductFor(m).price(it) }
    val ests = curve.map { cccvEstimate(state, m, it.price) }
    if (cccvToday != null && ests.all { it != null }) {
        return TodayVsFuture(cccvToday, curve.zip(ests.map { it!! }), true, "R$/saca", 2)
    }
    val q = state.quote(m) ?: return null
    return TodayVsFuture(q.price, curve.map { it to it.price }, false, m.unit, m.decimals)
}

/** Gráfico: "Hoje" + vencimentos, linha tracejada no preço de hoje, pontos verdes (acima) e vermelhos (abaixo). */
@Composable
fun TodayVsFutureChart(t: TodayVsFuture, height: Int = 210) {
    val values = listOf(t.today) + t.points.map { it.second }
    val colors = listOf(Gold) + t.points.map { (_, v) ->
        when {
            v > t.today * 1.001 -> Up
            v < t.today * 0.999 -> Down
            else -> Muted
        }
    }
    val last = values.lastIndex
    LineChart(
        series = listOf(
            ChartSeries(List(values.size) { t.today }, Muted, 1.2f, dashed = true),
            ChartSeries(values, Gold, 2.5f, pointColors = colors),
        ),
        modifier = Modifier.fillMaxWidth().height(height.dp),
        xLabels = buildList {
            add(0 to "Hoje")
            if (last >= 3) add(last / 2 to t.points[last / 2 - 1].first.expiry.label())
            if (last >= 1) add(last to t.points.last().first.expiry.label())
        },
        decimals = if (t.inCccv) 0 else t.decimals,
        showDots = true,
    )
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendDot(Gold, "Hoje")
        LegendDot(Up, "Acima de hoje")
        LegendDot(Down, "Abaixo de hoje")
    }
}

/** Leitura em linguagem simples, sempre comparando com o preço de hoje. Retorna o texto e o viés (-1, 0, +1). */
fun readingVsToday(t: TodayVsFuture): Pair<String, Int> {
    val above = t.points.filter { it.second > t.today * 1.001 }
    val below = t.points.filter { it.second < t.today * 0.999 }
    val best = t.points.maxBy { it.second }
    val worst = t.points.minBy { it.second }
    val bestTxt = "${best.first.expiry.label()} (${t.fmt(best.second)}, ${t.pctVsToday(best.second).pct(1)})"
    val worstTxt = "${worst.first.expiry.label()} (${t.fmt(worst.second)}, ${t.pctVsToday(worst.second).pct(1)})"
    return when {
        above.isEmpty() && below.isNotEmpty() ->
            "Todos os vencimentos estão ABAIXO do preço de hoje (${t.fmt(t.today)}). " +
                "O mercado espera preço menor no futuro. O menor é $worstTxt." to -1
        below.isEmpty() && above.isNotEmpty() ->
            "Todos os vencimentos estão ACIMA do preço de hoje (${t.fmt(t.today)}). " +
                "O mercado espera preço maior no futuro. O maior é $bestTxt." to 1
        above.isEmpty() && below.isEmpty() ->
            "Os vencimentos estão praticamente iguais ao preço de hoje (${t.fmt(t.today)})." to 0
        else ->
            "${above.size} vencimento(s) acima e ${below.size} abaixo do preço de hoje (${t.fmt(t.today)}). " +
                "Maior: $bestTxt. Menor: $worstTxt." to (if (above.size > below.size) 1 else if (below.size > above.size) -1 else 0)
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, color: Color = TextMain, align: TextAlign = TextAlign.End) {
    Text(text, Modifier.weight(weight), color = color, textAlign = align, style = MaterialTheme.typography.bodyMedium)
}

/** Diferença em R$ com sinal: "+R$ 12,30" / "−R$ 8,00". */
fun brlSigned(v: Double): String = (if (v >= 0) "+" else "−") + brl(kotlin.math.abs(v))

/**
 * Tabela de vencimentos comparada com hoje: preço estimado no CCCV e a diferença em R$/saca (e %).
 * O contrato de referência (o próprio "preço de hoje" da bolsa) já vem fora da curva.
 */
@Composable
fun FutureTable(state: UiState, m: Market, showBolsa: Boolean = false) {
    val t = todayVsFuture(state, m) ?: return
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Cell("Venc.", 0.8f, Muted, TextAlign.Start)
        if (showBolsa) Cell(m.unit, 0.9f, Muted)
        Cell(if (t.inCccv) "R$/saca" else m.unit, 1.1f, Muted)
        Cell("vs. hoje", 1.2f, Muted)
    }
    HorizontalDivider(color = CardBorder)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Cell("Hoje", 0.8f, TextMain, TextAlign.Start)
        if (showBolsa) Cell("—", 0.9f, Muted)
        Cell(t.fmt(t.today).removePrefix("R$ "), 1.1f, TextMain)
        Cell("—", 1.2f, Muted)
    }
    t.points.forEach { (c, v) ->
        val diff = v - t.today
        val pct = t.pctVsToday(v)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Cell(c.expiry.label(), 0.8f, Gold, TextAlign.Start)
            if (showBolsa) Cell(c.price.fmt(m.decimals), 0.9f)
            Cell(t.fmt(v).removePrefix("R$ "), 1.1f)
            Cell(
                if (t.inCccv) "${brlSigned(diff).replace("R$ ", "")} (${pct.pct(1)})" else pct.pct(1),
                1.2f, changeColor(pct),
            )
        }
    }
}

/** "Esperar compensa?": valor das sacas hoje × no vencimento que paga mais e no último vencimento. */
@Composable
private fun WaitCard(t: TodayVsFuture, settings: Settings, produto: String) {
    if (t.points.isEmpty()) return
    val sacas = settings.sacas.takeIf { it > 0 } ?: 100.0
    val best = t.points.maxBy { it.second }
    val last = t.points.last()
    SectionCard {
        SectionTitle("Esperar compensa?", "$produto · ${sacas.fmt(if (sacas % 1.0 == 0.0) 0 else 1)} sacas (altere na aba Calcular)")
        KeyValue("Vendendo hoje", brl(t.today * sacas), bold = true)
        val dBest = (best.second - t.today) * sacas
        KeyValue("No vencimento que paga mais (${best.first.expiry.label()})", brl(best.second * sacas))
        Text(
            (if (dBest >= 0) "▲ " else "▼ ") + brlSigned(dBest) + " em relação a hoje",
            color = changeColor(dBest), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
        )
        if (last.first != best.first) {
            val dLast = (last.second - t.today) * sacas
            KeyValue("No último vencimento (${last.first.expiry.label()})", brl(last.second * sacas) + "  (" + brlSigned(dLast) + ")", changeColor(dLast))
        }
        Text(
            if (dBest <= 0) "Pelo mercado futuro, nenhum vencimento paga mais do que o preço de hoje."
            else "Pelo mercado futuro, há vencimento pagando mais do que hoje. Lembre que isso exige travar o preço e que o dólar e o diferencial do CCCV podem mudar.",
            style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
