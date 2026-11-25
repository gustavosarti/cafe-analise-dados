package br.com.cafeanalise.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.cafeanalise.data.Boletim
import java.time.LocalDate

/** Rótulo e cor da tendência escrita pelo agente ("alta", "queda", "estavel"). */
fun tendenciaVisual(t: String): Pair<String, Color> = when (t.trim().lowercase()) {
    "alta" -> "▲ Futuro acima de hoje" to Up
    "queda" -> "▼ Futuro abaixo de hoje" to Down
    "estavel", "estável" -> "▶ Futuro parecido com hoje" to Muted
    else -> "" to Muted
}

fun efeitoCor(e: String): Color = when (e.trim().lowercase()) {
    "alta" -> Up
    "baixa", "queda" -> Down
    else -> Muted
}

/** "Boletim de hoje" ou "Boletim de 24/11" quando é de outro dia. */
fun boletimDataLabel(b: Boletim): String {
    val d = runCatching { LocalDate.parse(b.data) }.getOrNull() ?: return "Boletim do dia"
    return if (d == LocalDate.now()) "Boletim de hoje" else String.format("Boletim de %02d/%02d", d.dayOfMonth, d.monthValue)
}

/** Cartão compacto para a tela inicial. */
@Composable
fun BoletimCard(b: Boletim, onOpen: () -> Unit) {
    SectionCard(Modifier.clickable { onOpen() }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("BOLETIM DO DIA", fontSize = 12.sp, color = Gold, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(boletimDataLabel(b), fontSize = 12.sp, color = Muted)
        }
        if (b.titulo.isNotBlank()) Text(b.titulo, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(b.resumo, fontSize = 15.sp, color = TextMain, modifier = Modifier.padding(top = 4.dp))
        val (tend, cor) = tendenciaVisual(b.conilonTendencia)
        if (tend.isNotBlank()) {
            Text("Conilon: $tend", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cor, modifier = Modifier.padding(top = 8.dp))
        }
        Text("Ler boletim completo ›", fontSize = 14.sp, color = Gold, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Tela completa do boletim. */
@Composable
fun BoletimScreen(b: Boletim) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(boletimDataLabel(b), fontSize = 13.sp, color = Muted)
            if (b.titulo.isNotBlank()) Text(b.titulo, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(b.resumo, fontSize = 16.sp, color = TextMain, modifier = Modifier.padding(top = 6.dp))
        }

        // ---------------- conilon
        if (b.conilonFuturo.isNotBlank() || b.curtoPrazo.isNotBlank() || b.medioPrazo.isNotBlank()) {
            SectionCard {
                SectionTitle("Conilon: hoje × futuro")
                val (tend, cor) = tendenciaVisual(b.conilonTendencia)
                if (tend.isNotBlank()) Text(tend, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = cor)
                if (b.conilonVariacao.isNotBlank()) Text("Hoje: ${b.conilonVariacao}", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
                Paragraph(null, b.conilonFuturo)
                Paragraph("Próximas semanas", b.curtoPrazo)
                Paragraph("Próximos 3 a 6 meses", b.medioPrazo)
            }
        }

        // ---------------- arábica
        if (b.arabicaTexto.isNotBlank()) {
            SectionCard {
                SectionTitle("Arábica")
                val (tend, cor) = tendenciaVisual(b.arabicaTendencia)
                if (tend.isNotBlank()) Text(tend, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cor)
                Paragraph(null, b.arabicaTexto)
            }
        }

        // ---------------- fatores
        if (b.fatores.isNotEmpty()) {
            SectionCard {
                SectionTitle("O que está mexendo com o preço")
                b.fatores.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CardBorder)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.fator, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        val label = when (f.efeito.trim().lowercase()) {
                            "alta" -> "puxa para cima"
                            "baixa", "queda" -> "puxa para baixo"
                            else -> "neutro"
                        }
                        Pill(label, efeitoCor(f.efeito))
                    }
                    if (f.explicacao.isNotBlank()) Text(f.explicacao, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        // ---------------- o que observar
        if (b.oQueObservar.isNotEmpty()) {
            SectionCard {
                SectionTitle("O que acompanhar")
                b.oQueObservar.forEach { Text("• $it", fontSize = 15.sp, color = TextMain, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }

        if (b.commodities.isNotBlank()) {
            SectionCard {
                SectionTitle("Dólar e outras commodities")
                Text(b.commodities, fontSize = 15.sp, color = TextMain)
            }
        }

        // ---------------- notícias
        if (b.noticias.isNotEmpty()) {
            SectionCard {
                SectionTitle("Notícias", "Toque para abrir a matéria")
                b.noticias.forEachIndexed { i, n ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = CardBorder)
                    Column(Modifier.fillMaxWidth().clickable(enabled = n.url.startsWith("http")) { runCatching { uri.openUri(n.url) } }) {
                        Text(n.titulo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        if (n.resumo.isNotBlank()) Text(n.resumo, fontSize = 14.sp, color = TextMain, modifier = Modifier.padding(top = 2.dp))
                        Text(listOf(n.fonte, n.data).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = Gold, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        Disclaimer(b.aviso.ifBlank { "Análise informativa. Não é recomendação de compra ou venda." } + " Boletim escrito por um assistente de IA a partir dos preços do dia e de notícias.")
    }
}

@Composable
private fun Paragraph(title: String?, text: String) {
    if (text.isBlank()) return
    Column(Modifier.padding(top = 10.dp)) {
        if (title != null) Text(title, fontSize = 13.sp, color = Gold, fontWeight = FontWeight.SemiBold)
        Text(text, fontSize = 15.sp, color = TextMain)
    }
}
