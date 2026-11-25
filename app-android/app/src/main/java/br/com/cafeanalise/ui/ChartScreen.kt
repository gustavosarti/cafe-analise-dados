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
import br.com.cafeanalise.util.fmt
import br.com.cafeanalise.util.monthYear
import br.com.cafeanalise.util.pct
import br.com.cafeanalise.util.short

enum class Range(val label: String, val days: Int) {
    M1("1M", 22), M3("3M", 66), M6("6M", 130), Y1("1A", 260), Y2("2A", 520)
}

@Composable
fun ChartScreen(state: UiState) {
    var market by rememberSaveable { mutableStateOf(Market.ARABICA) }
    var range by rememberSaveable { mutableStateOf(Range.M6) }
    val q = state.quote(market)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MarketSelector(market) { market = it }
        ChipRow(Range.entries.toList(), range, { it.label }) { range = it }

        if (q == null || !q.hasHistory) {
            SectionCard {
                Text(
                    when {
                        state.loading -> "Carregando…"
                        q != null -> "A fonte gratuita da robusta (TradingView) não fornece o histórico diário, então não há gráfico. " +
                            "A cotação do dia e os indicadores estão nas abas Hoje e Projeção."
                        else -> "Sem dados para este mercado."
                    },
                    color = Muted,
                )
            }
        } else {
            ChartContent(q, market, range)
        }
    }
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
