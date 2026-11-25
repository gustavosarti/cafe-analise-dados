package br.com.cafeanalise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.cafeanalise.UiState
import br.com.cafeanalise.data.CccvProduct
import br.com.cafeanalise.data.CccvQuote
import br.com.cafeanalise.data.Market
import br.com.cafeanalise.data.Settings
import br.com.cafeanalise.util.BR
import br.com.cafeanalise.util.brl
import br.com.cafeanalise.util.fmt
import br.com.cafeanalise.util.label
import br.com.cafeanalise.util.parseNumber
import br.com.cafeanalise.util.pct

/**
 * Calculadora baseada no preço do CCCV (Vitória/ES), que é onde o café é vendido.
 * A bolsa entra só como referência: diferencial CCCV − bolsa, equilíbrio em ¢/lb ou US$/t e travas no futuro.
 */
@Composable
fun CalcScreen(state: UiState, settings: Settings, onChange: (Settings) -> Unit) {
    val p = settings.calcProduct
    var sacasTxt by rememberSaveable { mutableStateOf(settings.sacas.fmt(0)) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text("Calculadora de venda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Preços do CCCV (Vitória/ES)", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        ChipRow(CccvProduct.entries.toList(), p, { it.short }) { onChange(settings.copy(calcProduct = it)) }

        SectionCard {
            OutlinedTextField(
                value = sacasTxt,
                onValueChange = { t ->
                    sacasTxt = t
                    parseNumber(t)?.let { onChange(settings.copy(sacas = it)) }
                },
                label = { Text("Sacas para vender (60 kg)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val c = state.cccv?.getOrNull()
        val day = c?.last
        val price = day?.let { p.price(it) }
        if (c == null || day == null || price == null) {
            SectionCard {
                Text(
                    when {
                        state.cccv == null || state.loading -> "Carregando cotação do CCCV…"
                        c != null && day != null -> "O CCCV não publicou preço de ${p.title.lowercase(BR)} no último dia."
                        else -> "Não foi possível carregar a cotação do CCCV."
                    },
                    color = Muted,
                )
            }
        } else {
            CalcResults(state, settings, p, c, day.day, price)
        }
        Disclaimer("Valores brutos: não descontam impostos (Funrural), frete nem corretagem.")
    }
}

@Composable
private fun CalcResults(state: UiState, settings: Settings, p: CccvProduct, c: CccvQuote, day: Int, price: Double) {
    val sacas = settings.sacas
    val total = price * sacas
    val dateLabel = "$day de ${c.monthName.lowercase(BR)}"

    // Referência da bolsa: diferencial atual CCCV − bolsa, em R$/saca.
    val m = p.market
    val q = state.quote(m)
    val fx = state.usdBrl
    val basis = if (q != null && fx != null) price - m.usdPerBag(q.price) * fx else null
    val bolsaName = if (m == Market.ARABICA) "NY" else "Londres"

    // ---------------- venda hoje
    SectionCard {
        SectionTitle("Vendendo no CCCV", "${p.title} · cotação de $dateLabel")
        KeyValue("Preço por saca", brl(price))
        c.previous?.let { p.price(it) }?.takeIf { it != 0.0 }?.let { prev ->
            val chg = (price / prev - 1) * 100
            KeyValue("Variação vs. dia anterior", chg.pct(1), changeColor(chg))
        }
        p.average(c)?.takeIf { it != 0.0 }?.let { avg ->
            KeyValue("Média do mês", brl(avg))
            KeyValue("Valor com a média do mês", brl(avg * sacas))
        }
        if (basis != null) {
            KeyValue("Diferença CCCV x $bolsaName", (if (basis >= 0) "+" else "−") + brl(kotlin.math.abs(basis)) + "/saca")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CardBorder)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Valor total", color = Muted)
                Text("${sacas.fmt(if (sacas % 1.0 == 0.0) 0 else 1)} sacas × ${brl(price)}", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(brl(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Gold)
        }
    }

    // ---------------- travar no futuro (NY para arábica, Londres para conilon)
    val curve = state.curve(m)
    if (basis != null && fx != null && curve.isNotEmpty()) {
        SectionCard {
            SectionTitle(
                "Se travar no mercado futuro",
                "Estimativa: futuro de $bolsaName em R$ + diferencial de hoje (${if (basis >= 0) "+" else "−"}${brl(kotlin.math.abs(basis))})",
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("Venc.", Modifier.weight(0.8f), color = Muted)
                Text("Total", Modifier.weight(1.3f), color = Muted, textAlign = TextAlign.End)
                Text("vs. vender hoje", Modifier.weight(1.4f), color = Muted, textAlign = TextAlign.End)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Hoje", Modifier.weight(0.8f), color = TextMain)
                Text(brl(total), Modifier.weight(1.3f), textAlign = TextAlign.End)
                Text("—", Modifier.weight(1.4f), color = Muted, textAlign = TextAlign.End)
            }
            curve.forEach { cp ->
                val est = m.usdPerBag(cp.price) * fx + basis
                val diff = est * sacas - total
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(cp.expiry.label(), Modifier.weight(0.8f), color = Gold)
                    Text(brl(est * sacas), Modifier.weight(1.3f), textAlign = TextAlign.End)
                    Text(brlSigned(diff), Modifier.weight(1.4f), textAlign = TextAlign.End, color = changeColor(diff / total * 100))
                }
            }
            Text(
                "O diferencial e o dólar mudam com o tempo; use como referência.",
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
