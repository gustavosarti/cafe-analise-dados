package br.com.cafeanalise.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Uma saca = 60 kg = 132,277 libras-peso. */
const val LB_POR_SACA = 132.277

enum class Market(
    val title: String,
    val exchange: String,
    val unit: String,
    val decimals: Int,
) {
    ARABICA("Arábica", "ICE Nova York", "US¢/lb", 2),
    ROBUSTA("Robusta / Conilon", "ICE Londres", "US$/t", 0);

    /** Converte a cotação da bolsa para dólares por saca de 60 kg. */
    fun usdPerBag(price: Double): Double = when (this) {
        ARABICA -> price / 100.0 * LB_POR_SACA      // centavos de dólar por libra
        ROBUSTA -> price * 0.06                      // dólares por tonelada
    }

    /** Operação inversa: US$/saca -> unidade da bolsa. */
    fun priceFromUsdPerBag(usdBag: Double): Double = when (this) {
        ARABICA -> usdBag / LB_POR_SACA * 100.0
        ROBUSTA -> usdBag / 0.06
    }
}

data class PricePoint(val date: LocalDate, val close: Double)

/** Indicadores prontos (usados quando a fonte não fornece histórico diário, ex.: robusta). */
data class Technicals(
    val sma20: Double?,
    val sma50: Double?,
    val sma200: Double?,
    val rsi: Double?,
    val perf1M: Double?,
    val perfW: Double? = null,
    val perf3M: Double? = null,
    val perf6M: Double? = null,
    val perfY: Double? = null,
    val high52: Double? = null,
    val low52: Double? = null,
    /** Avaliação técnica do TradingView, de -1 (venda forte) a +1 (compra forte). */
    val rating: Double? = null,
)

data class Quote(
    val symbol: String,
    val name: String,
    val price: Double,
    val previousClose: Double?,
    val time: Instant,
    val history: List<PricePoint>,
    val technicals: Technicals? = null,
    val source: String = "Yahoo Finance",
) {
    val hasHistory: Boolean get() = history.size >= 30

    val change: Double? get() = previousClose?.let { price - it }
    val changePct: Double?
        get() = previousClose?.takeIf { it != 0.0 }?.let { (price - it) / it * 100.0 }

    /** Fechamentos com o preço atual no último ponto. */
    val closes: List<Double> get() = history.map { it.close }
}

/** Cotação diária do CCCV (Centro do Comércio de Café de Vitória), em R$/saca de 60 kg. */
data class CccvDay(val day: Int, val dura: Double?, val rio: Double?, val conilon: Double?)

data class CccvQuote(
    val monthName: String,
    val year: Int?,
    val days: List<CccvDay>,
    val avgDura: Double?,
    val avgRio: Double?,
    val avgConilon: Double?,
) {
    val last: CccvDay? get() = days.lastOrNull()
    val previous: CccvDay? get() = days.getOrNull(days.size - 2)
}

/** Produtos cotados pelo CCCV e a bolsa de referência de cada um. */
enum class CccvProduct(val title: String, val short: String, val market: Market) {
    DURA("Arábica dura", "Dura", Market.ARABICA),
    RIO("Arábica rio", "Rio", Market.ARABICA),
    CONILON("Conilon 7/8", "Conilon", Market.ROBUSTA);

    fun price(d: CccvDay): Double? = when (this) {
        DURA -> d.dura
        RIO -> d.rio
        CONILON -> d.conilon
    }

    fun average(c: CccvQuote): Double? = when (this) {
        DURA -> c.avgDura
        RIO -> c.avgRio
        CONILON -> c.avgConilon
    }
}

data class FxRate(val bid: Double, val changePct: Double?, val source: String)

data class CurvePoint(val symbol: String, val expiry: YearMonth, val price: Double)

data class Settings(
    val arabicaSymbol: String = "KC=F",
    /** Com ":" usa o TradingView (ex.: ICEEUR:RC1!); sem ":" usa o Yahoo Finance. */
    val robustaSymbol: String = "ICEEUR:RC1!",
    /** Diferencial do físico em relação à bolsa, em R$/saca (negativo = deságio). */
    val basisArabica: Double = 0.0,
    val basisRobusta: Double = 0.0,
    val sacas: Double = 100.0,
    val custoSaca: Double = 900.0,
    val calcProduct: CccvProduct = CccvProduct.DURA,
) {
    fun basis(m: Market) = if (m == Market.ARABICA) basisArabica else basisRobusta
    fun symbol(m: Market) = if (m == Market.ARABICA) arabicaSymbol else robustaSymbol
}

// ---------------------------------------------------------------- boletim diário (agente)

data class BoletimFator(val fator: String, val efeito: String, val explicacao: String)
data class BoletimNoticia(val titulo: String, val resumo: String, val fonte: String, val url: String, val data: String)

/** Boletim escrito todo dia pelo agente (repositório gustavosarti/cafe-analise-dados). */
data class Boletim(
    val data: String,
    val geradoEm: String,
    val titulo: String,
    val resumo: String,
    val conilonTendencia: String,
    val conilonVariacao: String,
    val conilonFuturo: String,
    val curtoPrazo: String,
    val medioPrazo: String,
    val arabicaTendencia: String,
    val arabicaTexto: String,
    val fatores: List<BoletimFator>,
    val commodities: String,
    val oQueObservar: List<String>,
    val noticias: List<BoletimNoticia>,
    val aviso: String,
    /** Expectativa escrita pelo agente: mercado mundial e Espírito Santo. */
    val mundoTendencia: String = "",
    val mundoTexto: String = "",
    val esTendencia: String = "",
    val esTexto: String = "",
) {
    val disponivel: Boolean get() = data.isNotBlank() && resumo.isNotBlank()
    val temExpectativa: Boolean get() = mundoTexto.isNotBlank() || esTexto.isNotBlank()
}

/** Um dia de preço físico no CCCV (R$/saca). */
data class CccvPoint(val date: java.time.LocalDate, val dura: Double?, val rio: Double?, val conilon: Double?)

private val MESES_PT = listOf(
    "janeiro", "fevereiro", "março", "abril", "maio", "junho",
    "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
)

/** Número do mês a partir do nome em português ("novembro" → 11). */
fun mesNumero(nome: String): Int? = MESES_PT.indexOf(nome.trim().lowercase()).takeIf { it >= 0 }?.plus(1)

/**
 * Série diária do CCCV: junta o histórico salvo pelo coletor (dados/historico.csv) com a tabela do mês atual.
 * A tabela do mês tem prioridade quando o mesmo dia aparece nos dois.
 */
fun cccvSerie(historico: List<CccvPoint>, mes: CccvQuote?): List<CccvPoint> {
    val map = sortedMapOf<java.time.LocalDate, CccvPoint>()
    historico.forEach { map[it.date] = it }
    val m = mes?.let { mesNumero(it.monthName) }
    val y = mes?.year
    if (mes != null && m != null && y != null) {
        mes.days.forEach { d ->
            runCatching { java.time.LocalDate.of(y, m, d.day) }.getOrNull()?.let { date ->
                map[date] = CccvPoint(date, d.dura, d.rio, d.conilon)
            }
        }
    }
    return map.values.toList()
}
