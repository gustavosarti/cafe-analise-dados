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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.cafeanalise.UiState
import br.com.cafeanalise.analysis.Analytics
import br.com.cafeanalise.data.Market
import br.com.cafeanalise.data.cccvSerie
import br.com.cafeanalise.util.brl
import br.com.cafeanalise.util.fmt
import br.com.cafeanalise.util.monthYear
import br.com.cafeanalise.util.pct
import br.com.cafeanalise.util.short

enum class Range(val label: String, val days: Int) {
    M1("1M", 22), M3("3M", 66), M6("6M", 130), Y1("1A", 260), Y2("2A", 520)
}

/** O que o gráfico mostra: físico no CCCV (onde a família vende) ou a bolsa de NY. */
enum class ChartSource(val label: String) { CONILON("Conilon CCCV"), DURA("Dura CCCV"), NY("Arábica NY") }

@Composable
fun ChartScreen(state: UiState) {
    var source by rememberSaveable { mutableStateOf(ChartSource.CONILON) }
    var range by rememberSaveable { mutableStateOf(Range.M6) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChipRow(ChartSource.entries.toList(), source, { it.label }) { source = it }

        if (source == ChartSource.NY) {
            ChipRow(Range.entries.toList(), range, { it.label }) { range = it }
            val q = state.quote(Market.ARABICA)
            if (q == null || !q.hasHistory) {
                SectionCard { Text(if (state.loading) "Carregando…" else "Sem dados de Nova York no momento.", color = Muted) }
            } else {
                ChartContent(q, Market.ARABICA, range)
            }
        } else {
            CccvChart(state, source)
        }
    }
}

/** Gráfico do preço físico no CCCV (histórico do coletor + tabela do mês). */
@Composable
private fun CccvChart(state: UiState, source: ChartSource) {
    val serie = cccvSerie(state.historico, state.cccv?.getOrNull())
        .mapNotNull { p -> (if (source == ChartSource.CONILON) p.conilon else p.dura)?.let { p.date to it } }
    val nome = if (source == ChartSource.CONILON) "Conilon 7/8" else "Arábica dura"
    if (serie.size < 2) {
        SectionCard { Text(if (state.loading) "Carregando…" else "Ainda não há dias suficientes do CCCV para o gráfico.", color = Muted) }
        return
    }
    val values = serie.map { it.second }
    val last = values.lastIndex
    SectionCard {
        SectionTitle("$nome no CCCV — R$/saca", "${serie.first().first.short()} a ${serie.last().first.short()} · ${serie.size} pregões")
        LineChart(
            series = listOf(ChartSeries(values, Gold, 2.5f)),
            modifier = Modifier.fillMaxWidth().height(240.dp),
            xLabels = buildList {
                add(0 to serie.first().first.short())
                if (last >= 4) add(last / 2 to serie[last / 2].first.short())
                add(last to serie.last().first.short())
            },
            decimals = 0,
            showDots = values.size <= 40,
        )
    }
    SectionCard {
        SectionTitle("Resumo do período")
        val varPct = Analytics.pctDiff(values.last(), values.first())
        val varRs = values.last() - values.first()
        KeyValue("Último preço (${serie.last().first.short()})", brl(values.last()), Gold, bold = true)
        KeyValue("Variação no período", "${brlSigned(varRs)} (${varPct.pct(1)})", changeColor(varPct), bold = true)
        KeyValue("Máxima", brl(values.max()))
        KeyValue("Mínima", brl(values.min()))
        KeyValue("Média", brl(values.average()))
        if (values.size >= 6) {
            val semana = Analytics.pctDiff(values.last(), values[values.size - 6])
            KeyValue("Últimos 5 pregões", semana.pct(1), changeColor(semana))
        }
    }
    SectionCard {
        SectionTitle("Últimos pregões")
        serie.takeLast(6).reversed().zipWithNext().forEach { (atual, anterior) ->
            val d = atual.second - anterior.second
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(atual.first.short(), Modifier.weight(1f), color = Gold)
                Text(brl(atual.second), Modifier.weight(1.2f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text(brlSigned(d), Modifier.weight(1.2f), color = changeColor(d), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
    Disclaimer("O histórico do CCCV cresce um dia por vez: o app junta a tabela do mês com os dias guardados pelo coletor desde 25/11/2026.")
}

@Composable
private fun ChartContent(q: br.com.cafeanalise.data.Quote, market: Market, range: Range) {
    run {
        val closes = q.closes
        val sma20 = Analytics.sma(closes, 20)
        val sma50 = Analytics.sma(closes, 50)
        val from = maxOf(0, closes.size - range.days)
        val slice = closes.subList(from, closes.size)
        val dates = q.history.subList(from, q.history.size).map { it.date }
        val fmtDate: (Int) -> String = { i ->
            if (range == Range.M1 || range == Range.M3) dates[i].short() else dates[i].monthYear()
        }
        val last = slice.size - 1

        SectionCard {
            SectionTitle("${market.title} — ${market.unit}", q.symbol)
            LineChart(
                series = listOf(
                    ChartSeries(sma50.subList(from, closes.size), Purple, 1.5f),
                    ChartSeries(sma20.subList(from, closes.size), Blue, 1.5f),
                    ChartSeries(slice, Gold, 2.5f),
                ),
                modifier = Modifier.fillMaxWidth().height(260.dp),
                xLabels = listOf(0 to fmtDate(0), last / 2 to fmtDate(last / 2), last to fmtDate(last)),
                decimals = market.decimals,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(Gold, "Preço")
                LegendDot(Blue, "Média 20d")
                LegendDot(Purple, "Média 50d")
            }
        }

        SectionCard {
            SectionTitle("Resumo do período (${range.label})")
            val max = slice.max()
            val min = slice.min()
            val varPct = Analytics.pctDiff(slice.last(), slice.first())
            KeyValue("Máxima", max.fmt(market.decimals) + " " + market.unit)
            KeyValue("Mínima", min.fmt(market.decimals) + " " + market.unit)
            KeyValue("Variação no período", varPct.pct(), changeColor(varPct), bold = true)
            KeyValue("Distância da máxima", Analytics.pctDiff(slice.last(), max).pct(1))
            Analytics.annualVol(slice, slice.size)?.let { KeyValue("Volatilidade anualizada", it.fmt(1) + "%") }
        }
        Disclaimer("Médias móveis: quando a linha do preço cruza as médias, costuma indicar mudança de tendência.")
    }
}
