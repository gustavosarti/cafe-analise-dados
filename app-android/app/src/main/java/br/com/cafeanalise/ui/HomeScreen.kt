package br.com.cafeanalise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.cafeanalise.UiState
import br.com.cafeanalise.data.CccvQuote
import br.com.cafeanalise.data.FxRate
import br.com.cafeanalise.data.Market
import br.com.cafeanalise.data.Quote
import br.com.cafeanalise.data.Settings
import br.com.cafeanalise.util.BR
import br.com.cafeanalise.util.brl
import br.com.cafeanalise.util.fmt
import br.com.cafeanalise.util.label
import br.com.cafeanalise.util.pct
import br.com.cafeanalise.analysis.Analytics
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(state: UiState, settings: Settings, onOpenBoletim: () -> Unit = {}) {
    val fx = state.fx?.getOrNull()
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 2.dp)) {
                val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", BR))
                Text(today.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    state.updatedAt?.let { "Atualizado às " + it.format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " · atualiza sozinho a cada minuto" } ?: "Carregando…",
                    fontSize = 13.sp, color = Muted,
                )
            }
        }

        // ---------------- Resumo simples do conilon (para quem não acompanha bolsa)
        item { ConilonSummary(state, settings) }

        // ---------------- Boletim do dia (agente)
        state.boletim?.getOrNull()?.takeIf { it.disponivel }?.let { b ->
            item { BoletimCard(b, onOpenBoletim) }
        }

        // ---------------- Espírito Santo
        item { CccvCard(state.cccv, state, fx) }

        // ---------------- Bolsas
        item {
            SectionCard {
                CardLabel("BOLSAS (REFERÊNCIA)")
                MarketRow(Market.ROBUSTA, "Robusta", "Londres", state.quotes[Market.ROBUSTA], fx, settings)
                RowDivider()
                MarketRow(Market.ARABICA, "Arábica", "Nova York", state.quotes[Market.ARABICA], fx, settings)
                RowDivider()
                FxRow(state.fx)
            }
        }

        item { Disclaimer("Bolsas com atraso de ~15 min. CCCV: preço do físico em Vitória/ES. Informação de apoio, não é recomendação.") }
    }
}

@Composable
private fun CardLabel(text: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.sp, color = Gold, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        if (sub != null) Text(sub, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(vertical = 2.dp), color = CardBorder)

/** Linha padrão: nome à esquerda, valor + variação à direita. */
@Composable
private fun PriceRow(
    title: String,
    subtitle: String?,
    value: String,
    unit: String?,
    changePct: Double?,
    subtitleColor: androidx.compose.ui.graphics.Color = Muted,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = subtitleColor)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (unit != null) Text(" $unit", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 2.dp))
            }
            if (changePct != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    (if (changePct > 0) "▲ " else if (changePct < 0) "▼ " else "") + changePct.pct(),
                    fontSize = 13.sp, color = changeColor(changePct), fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MarketRow(m: Market, name: String, place: String, res: Result<Quote>?, fx: FxRate?, settings: Settings) {
    val q = res?.getOrNull()
    when {
        res == null -> PriceRow(name, place, "…", null, null)
        q == null -> PriceRow(name, "$place · indisponível", "—", null, null, Down)
        else -> {
            val brlBag = fx?.let { m.usdPerBag(q.price) * it.bid + settings.basis(m) }
            PriceRow(
                title = "$name · $place",
                subtitle = brlBag?.let { brl(it) + "/saca" },
                value = q.price.fmt(m.decimals),
                unit = m.unit,
                changePct = q.changePct,
            )
        }
    }
}

@Composable
private fun FxRow(res: Result<FxRate>?) {
    val fx = res?.getOrNull()
    when {
        res == null -> PriceRow("Dólar", "comercial", "…", null, null)
        fx == null -> PriceRow("Dólar", "indisponível", "—", null, null, Down)
        else -> PriceRow("Dólar", "comercial", "R$ " + fx.bid.fmt(4), null, fx.changePct)
    }
}

@Composable
private fun CccvCard(res: Result<CccvQuote>?, state: UiState, fx: FxRate?) {
    val c = res?.getOrNull()
    val last = c?.last
    val prev = c?.previous
    SectionCard {
        CardLabel(
            "ESPÍRITO SANTO · CCCV",
            if (c != null && last != null) "${last.day} de ${c.monthName.lowercase(BR)}" else null,
        )
        when {
            res == null -> Text("Carregando…", color = Muted, modifier = Modifier.padding(vertical = 8.dp))
            c == null || last == null -> {
                Text("Não foi possível carregar a cotação do CCCV.", color = Down, modifier = Modifier.padding(top = 6.dp))
                Text(res?.exceptionOrNull()?.message ?: "", fontSize = 12.sp, color = Muted)
            }
            else -> {
                // Equivalente da bolsa em R$/saca (sem diferencial) para comparar com o físico.
                fun bolsa(m: Market): Double? {
                    val q = state.quote(m) ?: return null
                    val rate = fx?.bid ?: return null
                    return m.usdPerBag(q.price) * rate
                }
                CccvRow("Arábica dura", last.dura, prev?.dura, bolsa(Market.ARABICA), "NY")
                RowDivider()
                CccvRow("Arábica rio", last.rio, prev?.rio, bolsa(Market.ARABICA), "NY")
                RowDivider()
                CccvRow("Conilon 7/8", last.conilon, prev?.conilon, bolsa(Market.ROBUSTA), "Londres")
                val avg = listOfNotNull(
                    c.avgDura?.let { "dura ${it.fmt(0)}" },
                    c.avgRio?.let { "rio ${it.fmt(0)}" },
                    c.avgConilon?.let { "conilon ${it.fmt(0)}" },
                )
                if (avg.isNotEmpty()) {
                    Text(
                        "Média de ${c.monthName.lowercase(BR)}: " + avg.joinToString(" · "),
                        fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CccvRow(name: String, price: Double?, previous: Double?, bolsa: Double?, bolsaName: String) {
    if (price == null) {
        PriceRow(name, "sem cotação no dia", "—", null, null)
        return
    }
    val chg = previous?.takeIf { it != 0.0 }?.let { (price / it - 1) * 100 }
    val diff = bolsa?.let { price - it }
    PriceRow(
        title = name,
        subtitle = diff?.let { "vs. $bolsaName: " + (if (it >= 0) "+" else "−") + brl(kotlin.math.abs(it)) },
        value = brl(price),
        unit = null,
        changePct = chg,
    )
}

/**
 * Resumo em linguagem simples: preço do conilon no CCCV, se subiu ou caiu, quanto valem as sacas
 * e para onde o mercado futuro de Londres aponta.
 */
@Composable
private fun ConilonSummary(state: UiState, settings: Settings) {
    val c = state.cccv?.getOrNull()
    val last = c?.last
    val price = last?.conilon
    val prev = c?.previous?.conilon
    SectionCard {
        CardLabel("CONILON HOJE", last?.let { "CCCV · ${it.day} de ${c?.monthName?.lowercase(BR) ?: ""}" })
        if (price == null) {
            Text(if (state.cccv == null) "Carregando…" else "Preço do conilon indisponível no momento.", color = Muted)
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(brl(price), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Gold)
            Text("  a saca", fontSize = 15.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (prev != null && prev > 0) {
            val d = price - prev
            Text(
                when {
                    d > 0 -> "▲ Subiu ${brl(d)} desde o dia anterior"
                    d < 0 -> "▼ Caiu ${brl(-d)} desde o dia anterior"
                    else -> "Igual ao dia anterior"
                },
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = changeColor(d),
            )
        }
        val sacas = settings.sacas
        if (sacas > 0) {
            Text(
                "${sacas.fmt(if (sacas % 1.0 == 0.0) 0 else 1)} sacas valem hoje ${brl(price * sacas)}",
                fontSize = 15.sp, color = TextMain, modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Tendência: preço de hoje no CCCV comparado com os vencimentos de Londres (convertidos para o CCCV).
        val tvf = todayVsFuture(state, Market.ROBUSTA)
        if (tvf != null && tvf.points.isNotEmpty()) {
            RowDivider()
            val (_, b) = readingVsToday(tvf)
            val (icon, word, color) = when {
                b < 0 -> Triple("▼", "MENOR que hoje", Down)
                b > 0 -> Triple("▲", "MAIOR que hoje", Up)
                else -> Triple("▶", "parecido com hoje", Muted)
            }
            val min = tvf.points.minOf { it.second }
            val max = tvf.points.maxOf { it.second }
            Text("O que o mercado espera", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
            Text("$icon Preço futuro $word", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                "Nos próximos meses (até ${tvf.points.last().first.expiry.label()}), o mercado de Londres indica " +
                    (if (tvf.inCccv) "o conilon entre ${brl(min)} e ${brl(max)} a saca" else "a robusta entre ${min.fmt(0)} e ${max.fmt(0)} US$/t") +
                    ". É uma estimativa do mercado, não uma garantia.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
