package br.com.cafeanalise.util

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

val BR: Locale = Locale.forLanguageTag("pt-BR")

val MESES = listOf("Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")

fun Double.fmt(decimals: Int = 2): String = String.format(BR, "%,.${decimals}f", this)

fun Double.signed(decimals: Int = 2): String = (if (this > 0) "+" else "") + fmt(decimals)

fun Double.pct(decimals: Int = 2): String {
    // Evita "-0,0%": valores que arredondam para zero viram zero.
    val r = if (kotlin.math.abs(this) < 0.5 * Math.pow(10.0, -decimals.toDouble())) 0.0 else this
    return r.signed(decimals) + "%"
}

fun brl(v: Double): String = "R$ " + v.fmt(2)

fun usd(v: Double): String = "US$ " + v.fmt(2)

fun YearMonth.label(): String = MESES[monthValue - 1] + "/" + String.format(BR, "%02d", year % 100)

fun LocalDate.short(): String = String.format(BR, "%02d/%02d", dayOfMonth, monthValue)

fun LocalDate.monthYear(): String = MESES[monthValue - 1] + "/" + String.format(BR, "%02d", year % 100)

/** Aceita "1.234,56", "1234.56", "1234,5" etc. */
fun parseNumber(text: String): Double? {
    val t = text.trim().replace(" ", "")
    if (t.isEmpty()) return null
    val normalized = when {
        t.contains(',') -> t.replace(".", "").replace(',', '.')
        // "1.500" ou "-12.000.000" = separador de milhar no padrão brasileiro
        Regex("^-?\\d{1,3}(\\.\\d{3})+$").matches(t) -> t.replace(".", "")
        else -> t
    }
    return normalized.toDoubleOrNull()
}
