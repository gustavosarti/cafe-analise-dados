package br.com.cafeanalise.analysis

import br.com.cafeanalise.data.Quote
import br.com.cafeanalise.data.Technicals
import br.com.cafeanalise.util.fmt
import br.com.cafeanalise.util.pct
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class Signal(
    val name: String,
    val value: String,
    /** +1 alta, 0 neutro, -1 baixa */
    val bias: Int,
    val detail: String,
)

data class Projection(
    val days: Int,
    val label: String,
    val center: Double,
    val low68: Double,
    val high68: Double,
    val low95: Double,
    val high95: Double,
)

data class Forecast(
    val last: Double,
    /** tendência diária em log (regressão dos últimos pregões) */
    val dailyDrift: Double,
    /** desvio-padrão diário dos retornos em log */
    val dailyVol: Double,
    val lookback: Int,
) {
    val annualVolPct: Double get() = dailyVol * sqrt(252.0) * 100.0
    val monthlyTrendPct: Double get() = (exp(dailyDrift * 21) - 1) * 100.0

    fun at(days: Int, label: String = ""): Projection {
        val c = last * exp(dailyDrift * days)
        val s = dailyVol * sqrt(days.toDouble())
        return Projection(days, label, c, c * exp(-s), c * exp(s), c * exp(-1.96 * s), c * exp(1.96 * s))
    }

    val projections: List<Projection>
        get() = listOf(21 to "1 mês", 63 to "3 meses", 126 to "6 meses").map { (d, l) -> at(d, l) }
}

data class Bias(val label: String, val score: Int)

object Analytics {

    fun sma(v: List<Double>, n: Int): List<Double?> {
        val out = ArrayList<Double?>(v.size)
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= n) sum -= v[i - n]
            out += if (i + 1 >= n) sum / n else null
        }
        return out
    }

    /** RSI de Wilder. */
    fun rsi(v: List<Double>, n: Int = 14): Double? {
        if (v.size <= n) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = v[i] - v[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= n; loss /= n
        for (i in n + 1 until v.size) {
            val d = v[i] - v[i - 1]
            gain = (gain * (n - 1) + maxOf(d, 0.0)) / n
            loss = (loss * (n - 1) + maxOf(-d, 0.0)) / n
        }
        if (loss == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + gain / loss)
    }

    fun logReturns(v: List<Double>): List<Double> = (1 until v.size).map { ln(v[it] / v[it - 1]) }

    fun stdev(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    /** Volatilidade anualizada (%) dos últimos [n] pregões. */
    fun annualVol(v: List<Double>, n: Int = 60): Double? {
        if (v.size < 10) return null
        return stdev(logReturns(v.takeLast(n + 1))) * sqrt(252.0) * 100.0
    }

    /**
     * Projeção estatística: tendência (regressão linear do log-preço nos últimos [lookback] pregões)
     * + faixas de volatilidade histórica (modelo log-normal). Não é recomendação de investimento.
     */
    fun forecast(closes: List<Double>, lookback: Int = 120): Forecast? {
        if (closes.size < 40) return null
        val w = closes.takeLast(lookback)
        val y = w.map { ln(it) }
        val n = y.size
        val xm = (n - 1) / 2.0
        val ym = y.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (i - xm) * (y[i] - ym)
            sxx += (i - xm) * (i - xm)
        }
        val slope = if (sxx == 0.0) 0.0 else sxy / sxx
        val vol = stdev(logReturns(w))
        return Forecast(closes.last(), slope, vol, n)
    }

    fun signals(c: List<Double>): List<Signal> {
        if (c.size < 2) return emptyList()
        return buildSignals(
            last = c.last(),
            s20 = if (c.size >= 20) c.takeLast(20).average() else null,
            s50 = if (c.size >= 50) c.takeLast(50).average() else null,
            s200 = if (c.size >= 200) c.takeLast(200).average() else null,
            rsi = rsi(c),
            momentumPct = if (c.size >= 21) (c.last() / c[c.size - 21] - 1) * 100 else null,
        )
    }

    /** Sinais a partir de indicadores prontos (fonte sem histórico, ex.: TradingView). */
    fun signals(price: Double, t: Technicals): List<Signal> =
        buildSignals(price, t.sma20, t.sma50, t.sma200, t.rsi, t.perf1M)

    /** Sinais da cotação, usando o histórico ou, na falta dele, os indicadores prontos. */
    fun signals(q: Quote): List<Signal> =
        if (q.hasHistory) signals(q.closes) else q.technicals?.let { signals(q.price, it) } ?: emptyList()

    private fun buildSignals(
        last: Double,
        s20: Double?,
        s50: Double?,
        s200: Double?,
        rsi: Double?,
        momentumPct: Double?,
    ): List<Signal> {
        val out = mutableListOf<Signal>()
        if (s50 != null && s50 > 0) {
            val up = last > s50
            out += Signal(
                "Preço × média de 50 dias", ((last / s50 - 1) * 100).pct(1), if (up) 1 else -1,
                if (up) "Preço acima da média de 50 dias: tendência de médio prazo positiva."
                else "Preço abaixo da média de 50 dias: tendência de médio prazo negativa."
            )
        }
        if (s200 != null && s200 > 0) {
            val up = last > s200
            out += Signal(
                "Preço × média de 200 dias", ((last / s200 - 1) * 100).pct(1), if (up) 1 else -1,
                if (up) "Acima da média de 200 dias: tendência de longo prazo de alta."
                else "Abaixo da média de 200 dias: tendência de longo prazo de baixa."
            )
        }
        if (s20 != null && s50 != null) {
            val up = s20 > s50
            out += Signal(
                "Média 20 × média 50", if (up) "20 acima" else "20 abaixo", if (up) 1 else -1,
                if (up) "Média curta acima da longa: momento comprador."
                else "Média curta abaixo da longa: momento vendedor."
            )
        }
        if (rsi != null) {
            val (b, txt) = when {
                rsi >= 70 -> -1 to "Sobrecomprado (RSI ≥ 70): subida forte recente, risco maior de correção."
                rsi <= 30 -> 1 to "Sobrevendido (RSI ≤ 30): queda forte recente, chance de repique."
                else -> 0 to "RSI em zona neutra (entre 30 e 70)."
            }
            out += Signal("RSI 14 dias", rsi.fmt(0), b, txt)
        }
        if (momentumPct != null) {
            val b = when {
                momentumPct > 2 -> 1
                momentumPct < -2 -> -1
                else -> 0
            }
            out += Signal(
                "Momento (1 mês)", momentumPct.pct(1), b,
                when (b) {
                    1 -> "Alta acumulada no último mês."
                    -1 -> "Queda acumulada no último mês."
                    else -> "Preço praticamente estável no último mês."
                }
            )
        }
        return out
    }

    fun bias(signals: List<Signal>): Bias {
        val score = signals.sumOf { it.bias }
        val label = when {
            score >= 2 -> "Viés de alta"
            score <= -2 -> "Viés de baixa"
            else -> "Neutro"
        }
        return Bias(label, score)
    }

    /** Diferença percentual entre dois valores. */
    fun pctDiff(a: Double, b: Double): Double = if (b == 0.0) 0.0 else (a / b - 1) * 100

    fun isSignificant(pct: Double) = abs(pct) >= 0.5
}
