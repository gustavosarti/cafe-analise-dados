package br.com.cafeanalise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.cafeanalise.data.Settings
import br.com.cafeanalise.util.fmt
import br.com.cafeanalise.util.parseNumber

@Composable
fun SettingsScreen(settings: Settings, onSave: (Settings) -> Unit, onDone: () -> Unit) {
    var arabica by rememberSaveable { mutableStateOf(settings.arabicaSymbol) }
    var robusta by rememberSaveable { mutableStateOf(settings.robustaSymbol) }
    var basisA by rememberSaveable { mutableStateOf(settings.basisArabica.fmt(2)) }
    var basisR by rememberSaveable { mutableStateOf(settings.basisRobusta.fmt(2)) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            SectionTitle("Diferencial do físico (R$/saca)", "Diferença entre o preço pago na sua região e o equivalente da bolsa. Use negativo para deságio.")
            OutlinedTextField(
                basisA, { basisA = it }, label = { Text("Arábica") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                basisR, { basisR = it }, label = { Text("Robusta / Conilon") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "Exemplo: se a cooperativa paga R$ 1.800 e o app mostra R$ 1.950 (bolsa × dólar), o diferencial é -150.",
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionCard {
            SectionTitle("Símbolos", "Só altere se uma cotação não carregar. Com \":\" usa o TradingView; sem, o Yahoo Finance.")
            OutlinedTextField(arabica, { arabica = it }, label = { Text("Arábica NY") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                robusta, { robusta = it }, label = { Text("Robusta Londres") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        Button(
            onClick = {
                onSave(
                    settings.copy(
                        arabicaSymbol = arabica.trim().ifEmpty { "KC=F" },
                        robustaSymbol = robusta.trim().ifEmpty { "ICEEUR:RC1!" },
                        basisArabica = parseNumber(basisA) ?: settings.basisArabica,
                        basisRobusta = parseNumber(basisR) ?: settings.basisRobusta,
                    )
                )
                onDone()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Salvar") }

        SectionCard {
            SectionTitle("Fontes de dados")
            Text(
                "• Arábica (ICE NY): Yahoo Finance, atraso de ~15 min.\n" +
                    "• Robusta (ICE Londres): TradingView, sem histórico diário.\n" +
                    "• Espírito Santo: CCCV (cccv.org.br/cotacao), físico em R$/saca.\n" +
                    "• Dólar: AwesomeAPI (economia.awesomeapi.com.br), com Yahoo como reserva.\n" +
                    "• Conversões: 1 saca = 60 kg = 132,277 lb; robusta em US$/t × 0,06.",
                style = MaterialTheme.typography.bodySmall, color = TextMain,
            )
        }
    }
}
