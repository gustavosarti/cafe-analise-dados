package br.com.cafeanalise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.cafeanalise.UiState
import br.com.cafeanalise.analysis.Analytics
import br.com.cafeanalise.analysis.Forecast
import br.com.cafeanalise.data.Market
import br.com.cafeanalise.data.Quote
import br.com.cafeanalise.data.Settings
import br.com.cafeanalise.util.brl
import br.com.cafeanalise.util.fmt
import br.com.cafeanalise.util.label
import br.com.cafeanalise.util.pct
import br.com.cafeanalise.util.short
import java.time.YearMonth

private const val HIST = 120
private const val HORIZON = 126

@Composable
fun ForecastScreen(state: UiState, settings: Settings) {
    var market by rememberSaveable { mutableStateOf(Market.ROBUSTA) }
    val q = state.quote(market)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MarketSelector(market) { market = it }
        val f = q?.takeIf { it.hasHistory }?.let { Analytics.forecast(it.closes, HIST) }
        if (q != null && f == null && q.technicals != null) {
            RobustaOutlook(state, q)
        } else if (q == null || f == null) {
            SectionCard { Text(if (state.loading) "Carregando…" else "Dados insuficientes para projeção.", color = Muted) }
        } else {
            ForecastContent(state, settings, market, q, f)
        }
        Disclaimer(
            "Projeção estatística baseada só no histórico de preços (tendência + volatilidade). " +
                "Não considera clima, safra, estoques ou notícias. Use como referência, não como garantia."
        )
    }
}

@Composable
private fun ForecastContent(state: UiState, settings: Settings, m: Market, q: Quote, f: Forecast) {
    val signals = Analytics.signals(q)
    val bias = Analytics.bias(signals)
    val fx = state.usdBrl

    // ---- resumo
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Leitura técnica", style = MaterialTheme.typography.titleMedium, color = Gold, fontWeight = FontWeight.Bold)
                Text("${m.title} · ${q.price.fmt(m.decimals)} ${m.unit}", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Pill(bias.label, biasColor(bias.score))
        }
        Spacer(Modifier.height(8.dp))
        KeyValue("Tendência recente (por mês)", f.monthlyTrendPct.pct(1), changeColor(f.monthlyTrendPct), bold = true)
        KeyValue("Volatilidade anualizada", f.annualVolPct.fmt(1) + "%")
    }

    // ---- gráfico em leque
    SectionCard {
        SectionTitle("Projeção para 6 meses", "Linha tracejada: tendência · faixas: 68% e 95% de probabilidade")
        val hist = q.closes.takeLast(HIST)
        val total = hist.size + HORIZON
        val lastIdx = hist.size - 1
        fun proj(sel: (Int) -> Double): List<Double?> =
            List(total) { i -> if (i < lastIdx) null else if (i == lastIdx) hist.last() else sel(i - lastIdx) }

        LineChart(
            series = listOf(
                ChartSeries(hist + List(HORIZON) { null }, Gold, 2.5f),
                ChartSeries(proj { f.at(it).center }, TextMain, 1.5f, dashed = true),
            ),
            bands = listOf(
                ChartBand(proj { f.at(it).low95 }, proj { f.at(it).high95 }, Gold.copy(alpha = 0.10f)),
                ChartBand(proj { f.at(it).low68 }, proj { f.at(it).high68 }, Gold.copy(alpha = 0.20f)),
            ),
            modifier = Modifier.fillMaxWidth().height(260.dp),
            xLabels = listOf(
                0 to q.history[q.history.size - hist.size].date.short(),
                lastIdx to "hoje",
                total - 1 to "+6 meses",
            ),
            decimals = m.decimals,
        )
    }

    // ---- tabela
    SectionCard {
        SectionTitle("Cenários (${m.unit})", if (fx != null) "R$/saca com o dólar de hoje" else null)
        f.projections.forEach { p ->
            Text(p.label, color = Gold, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            KeyValue("Tendência", p.center.fmt(m.decimals) + (fx?.let { "  ·  " + brl(m.usdPerBag(p.center) * it + settings.basis(m)) } ?: ""))
            KeyValue("Faixa provável (68%)", "${p.low68.fmt(m.decimals)} – ${p.high68.fmt(m.decimals)}")
            KeyValue("Faixa ampla (95%)", "${p.low95.fmt(m.decimals)} – ${p.high95.fmt(m.decimals)}")
            HorizontalDivider(Modifier.padding(top = 6.dp), color = CardBorder)
        }

        // comparação com o mercado futuro (só arábica)
        if (m == Market.ARABICA && state.curve.isNotEmpty()) {
            val target = YearMonth.now().plusMonths(3)
            val c = state.curve.minByOrNull { kotlin.math.abs(it.expiry.toEpochMonth() - target.toEpochMonth()) }
            if (c != null) {
                Text(
                    "Mercado futuro: o vencimento ${c.expiry.label()} está em ${c.price.fmt(2)} ¢/lb " +
                        "(${Analytics.pctDiff(c.price, q.price).pct(1)} vs. hoje). Compare com o cenário de 3 meses.",
                    style = MaterialTheme.typography.bodySmall, color = TextMain, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    // ---- sinais
    SectionCard {
        SectionTitle("Indicadores")
        signals.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Pill(s.value, biasColor(s.bias))
            }
            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

/**
 * Robusta/conilon: a fonte não tem histórico diário, então a "projeção" vem do próprio mercado futuro de Londres
 * (curva de vencimentos), convertida para o CCCV, mais desempenho, indicadores técnicos e o conilon no CCCV no mês.
 */
@Composable
private fun RobustaOutlook(state: UiState, q: Quote) {
    val m = Market.ROBUSTA
    val t = q.technicals
    val curve = state.robustaCurve
    val signals = Analytics.signals(q)
    val bias = Analytics.bias(signals)
    val c = state.cccv?.getOrNull()
    val conilonHoje = c?.last?.conilon

    // ---------------- para onde o mercado aponta
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Para onde o mercado aponta", style = MaterialTheme.typography.titleMedium, color = Gold, fontWeight = FontWeight.Bold)
                Text(
                    conilonHoje?.let { "Conilon no CCCV hoje: ${brl(it)} a saca" } ?: "Robusta Londres · ${q.price.fmt(0)} ${m.unit} hoje",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
        }
        val tvf = todayVsFuture(state, m)
        if (curve.size < 2 || tvf == null) {
            Text(
                if (state.curveLoading) "Carregando vencimentos…" else "Curva de vencimentos de Londres indisponível no momento.",
                color = Muted, modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            val (txt, b) = readingVsToday(tvf)
            Text(
                txt, color = biasColor(b), fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )
            TodayVsFutureChart(tvf, 190)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Venc.", Modifier.weight(0.9f), color = Muted)
                Text("CCCV est.", Modifier.weight(1.6f), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("vs. hoje", Modifier.weight(0.9f), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            curve.forEach { cp ->
                val est = cccvEstimate(state, m, cp.price)
                val vs = if (est != null && conilonHoje != null) Analytics.pctDiff(est, conilonHoje) else Analytics.pctDiff(cp.price, q.price)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(cp.expiry.label(), Modifier.weight(0.9f), color = Gold)
                    Text(est?.let { brl(it) } ?: "${cp.price.fmt(0)} ${m.unit}", Modifier.weight(1.6f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(vs.pct(1), Modifier.weight(0.9f), color = changeColor(vs), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            Text(
                "Conilon CCCV estimado = futuro de Londres em R$/saca + diferencial de hoje entre o CCCV e Londres" +
                    (cccvBasis(state, m)?.let { " (${if (it >= 0) "+" else "−"}${brl(kotlin.math.abs(it))})" } ?: "") +
                    ", com o dólar de hoje.",
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    // ---------------- desempenho
    if (t != null) {
        SectionCard {
            SectionTitle("Desempenho da robusta em Londres")
            listOf(
                "1 semana" to t.perfW, "1 mês" to t.perf1M, "3 meses" to t.perf3M,
                "6 meses" to t.perf6M, "1 ano" to t.perfY,
            ).forEach { (label, v) -> if (v != null) KeyValue(label, v.pct(1), changeColor(v), bold = true) }
            val hi = t.high52
            val lo = t.low52
            if (hi != null && lo != null && hi > lo) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = CardBorder)
                KeyValue("Máxima de 52 semanas", hi.fmt(0) + " " + m.unit)
                KeyValue("Mínima de 52 semanas", lo.fmt(0) + " " + m.unit)
                val pos = ((q.price - lo) / (hi - lo) * 100).coerceIn(0.0, 100.0)
                KeyValue("Posição na faixa do ano", pos.fmt(0) + "% (0% = mínima, 100% = máxima)")
            }
        }
    }

    // ---------------- leitura técnica
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Leitura técnica", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Gold, fontWeight = FontWeight.Bold)
            Pill(bias.label, biasColor(bias.score))
        }
        t?.rating?.let { r ->
            val (label, b) = when {
                r >= 0.5 -> "Compra forte" to 1
                r >= 0.1 -> "Compra" to 1
                r > -0.1 -> "Neutro" to 0
                r > -0.5 -> "Venda" to -1
                else -> "Venda forte" to -1
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sinal técnico (TradingView)", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Pill(label, biasColor(b))
            }
            Text("Resumo de médias móveis e osciladores calculado pelo TradingView.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        signals.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Pill(s.value, biasColor(s.bias))
            }
            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }

    // ---------------- conilon no CCCV no mês
    val days = c?.days?.filter { it.conilon != null }.orEmpty()
    if (c != null && days.size >= 2) {
        SectionCard {
            SectionTitle("Conilon no CCCV — ${c.monthName.lowercase(br.com.cafeanalise.util.BR)}", "R$/saca, preços diários de Vitória")
            val values = days.map { it.conilon!! }
            LineChart(
                series = listOf(ChartSeries(values, Gold, 2.5f)),
                modifier = Modifier.fillMaxWidth().height(170.dp),
                xLabels = listOf(0 to "dia ${days.first().day}", days.lastIndex to "dia ${days.last().day}"),
                decimals = 0,
                showDots = true,
            )
            val varMes = Analytics.pctDiff(values.last(), values.first())
            KeyValue("Variação no mês", varMes.pct(1), changeColor(varMes), bold = true)
            KeyValue("Máxima / mínima", "${brl(values.max())} / ${brl(values.min())}")
            c.avgConilon?.let { KeyValue("Média do mês", brl(it)) }
        }
    }
}

private fun YearMonth.toEpochMonth(): Int = year * 12 + monthValue
