package br.com.cafeanalise.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Fontes gratuitas, sem chave de API:
 *  - Yahoo Finance (gráfico v8): futuros de café na ICE (KC=F e contratos KCxYY.NYB) e dólar (BRL=X).
 *  - AwesomeAPI: cotação do dólar comercial (USD-BRL).
 * As cotações de bolsa no Yahoo têm atraso de ~10-15 minutos.
 */
object MarketApi {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val NY: ZoneId = ZoneId.of("America/New_York")

    private fun get(url: String, accept: String = "application/json"): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", accept)
        try {
            val code = conn.responseCode
            if (code == 404) throw IOException("símbolo não encontrado")
            if (code !in 200..299) throw IOException("servidor respondeu HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, json: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Origin", "https://www.tradingview.com")
        conn.setRequestProperty("Referer", "https://www.tradingview.com/")
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("servidor respondeu HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Cotação conforme o tipo de símbolo: com ":" → TradingView; sem → Yahoo Finance. */
    fun quote(symbol: String): Quote =
        if (symbol.contains(':')) tradingView(symbol) else yahooChart(symbol)

    /** Consulta o scanner público do TradingView. Retorna, por ticker, a lista de valores das colunas. */
    private fun tvScan(tickers: List<String>, columns: List<String>): Map<String, JSONArray> {
        val tk = JSONArray()
        tickers.forEach { tk.put(it) }
        val body = JSONObject()
            .put("symbols", JSONObject()
                .put("tickers", tk)
                .put("query", JSONObject().put("types", JSONArray())))
            .put("columns", JSONArray(columns))
            .toString()
        var lastError: Exception? = null
        for (market in listOf("futures", "global")) {
            try {
                val data = JSONObject(post("https://scanner.tradingview.com/$market/scan", body)).optJSONArray("data")
                if (data == null || data.length() == 0) throw IOException("símbolo não encontrado")
                val out = LinkedHashMap<String, JSONArray>()
                for (i in 0 until data.length()) {
                    val o = data.getJSONObject(i)
                    out[o.optString("s")] = o.getJSONArray("d")
                }
                return out
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("falha no TradingView")
    }

    private fun JSONArray.num(i: Int): Double? =
        if (i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }

    /**
     * Robusta (ICE Londres) pelo scanner público do TradingView: preço, variação do dia e indicadores.
     * Essa fonte não fornece histórico diário.
     */
    fun tradingView(ticker: String): Quote {
        val core = listOf("close", "change_abs", "description", "SMA20", "SMA50", "SMA200", "RSI", "Perf.1M")
        val d = tvScan(listOf(ticker), core).values.firstOrNull() ?: throw IOException("símbolo não encontrado")
        val close = d.num(0) ?: throw IOException("sem preço para $ticker")
        val change = d.num(1)

        // Indicadores extras numa consulta separada: se algum campo não existir, o principal continua funcionando.
        val extra = runCatching {
            tvScan(
                listOf(ticker),
                listOf("Perf.W", "Perf.3M", "Perf.6M", "Perf.Y", "price_52_week_high", "price_52_week_low", "Recommend.All"),
            ).values.firstOrNull()
        }.getOrNull()

        return Quote(
            symbol = ticker,
            name = d.optString(2).ifBlank { ticker },
            price = close,
            previousClose = change?.let { close - it },
            time = Instant.now(),
            history = listOf(PricePoint(LocalDate.now(), close)),
            technicals = Technicals(
                sma20 = d.num(3), sma50 = d.num(4), sma200 = d.num(5), rsi = d.num(6), perf1M = d.num(7),
                perfW = extra?.num(0), perf3M = extra?.num(1), perf6M = extra?.num(2), perfY = extra?.num(3),
                high52 = extra?.num(4), low52 = extra?.num(5), rating = extra?.num(6),
            ),
            source = "TradingView",
        )
    }

    /**
     * Primeiro mês de vencimento ainda "vivo": exclui o mês atual e, a partir do dia 20, também o mês seguinte
     * (contratos já em período de entrega têm preço distorcido e pouca liquidez).
     */
    private fun firstLiveMonth(today: LocalDate): YearMonth =
        YearMonth.from(today).plusMonths(if (today.dayOfMonth >= 20) 2 else 1)

    /** Vencimentos da robusta em Londres: Jan(F), Mar(H), Mai(K), Jul(N), Set(U), Nov(X). */
    private val ROBUSTA_MONTHS = mapOf(1 to 'F', 3 to 'H', 5 to 'K', 7 to 'N', 9 to 'U', 11 to 'X')

    fun robustaContracts(today: LocalDate, count: Int = 8): List<Pair<String, YearMonth>> {
        val out = mutableListOf<Pair<String, YearMonth>>()
        var ym = firstLiveMonth(today)
        while (out.size < count) {
            ROBUSTA_MONTHS[ym.monthValue]?.let { code -> out += "ICEEUR:RC$code${ym.year}" to ym }
            ym = ym.plusMonths(1)
        }
        return out
    }

    /** Curva de futuros da robusta (US$/t) em uma única consulta ao TradingView. */
    fun robustaCurve(today: LocalDate): List<CurvePoint> {
        val contracts = robustaContracts(today)
        val data = tvScan(contracts.map { it.first }, listOf("close"))
        return contracts.mapNotNull { (sym, ym) ->
            data[sym]?.num(0)?.takeIf { it > 0 }?.let { CurvePoint(sym, ym, it) }
        }
    }

    /**
     * Cotação do CCCV (Vitória/ES): tabela do mês com arábica dura, arábica rio e conilon, em R$/saca.
     * Os dados vêm direto do HTML de https://www.cccv.org.br/cotacao/
     */
    fun cccv(): CccvQuote {
        // O site do CCCV às vezes demora: tenta uma segunda vez antes de desistir.
        val html = runCatching { get("https://www.cccv.org.br/cotacao/", "text/html") }
            .getOrElse { Thread.sleep(1500); get("https://www.cccv.org.br/cotacao/", "text/html") }
        fun clean(s: String) = s
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&#160;", " ")
            .replace("&quot;", "\"").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()

        val title = Regex("refer\\S*\\s+ao\\s+m\\S*s\\s+de\\s+(\\p{L}+)\\s+de\\s+(\\d{4})", RegexOption.IGNORE_CASE)
            .find(clean(html))
        val monthName = title?.groupValues?.get(1) ?: ""
        val year = title?.groupValues?.get(2)?.toIntOrNull()

        val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        val rowRe = Regex("<tr[^>]*>(.*?)</tr>", opts)
        val cellRe = Regex("<t[dh][^>]*>(.*?)</t[dh]>", opts)

        val days = mutableListOf<CccvDay>()
        var avg: List<Double?>? = null
        for (row in rowRe.findAll(html)) {
            val cells = cellRe.findAll(row.groupValues[1]).map { clean(it.groupValues[1]) }
                .filter { it.isNotEmpty() }.toList()
            if (cells.size < 4) continue
            val first = cells[0]
            val values = cells.drop(1).take(3).map { v -> if (v == "-") null else br.com.cafeanalise.util.parseNumber(v) }
            when {
                Regex("^\\d{1,2}$").matches(first) ->
                    days += CccvDay(first.toInt(), values[0], values[1], values[2])
                first.startsWith("m", ignoreCase = true) && first.contains("dia", ignoreCase = true) ->
                    avg = values
            }
        }
        // Considera só dias com algum preço (feriados vêm com "-").
        val valid = days.filter { it.dura != null || it.rio != null || it.conilon != null }.sortedBy { it.day }
        if (valid.isEmpty()) throw IOException("tabela de cotação não encontrada")
        return CccvQuote(monthName, year, valid, avg?.getOrNull(0), avg?.getOrNull(1), avg?.getOrNull(2))
    }

    /**
     * Histórico diário do CCCV gravado pelo coletor (dados/historico.csv).
     * Colunas: data,cccv_dia,conilon,dura,rio,... — "cccv_dia" é o dia do preço publicado pelo CCCV.
     */
    fun historico(): List<CccvPoint> {
        val csv = get("https://raw.githubusercontent.com/gustavosarti/cafe-analise-dados/main/dados/historico.csv", "text/plain")
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val head = lines.first().split(',')
        fun idx(n: String) = head.indexOf(n)
        val iData = idx("data"); val iDia = idx("cccv_dia"); val iCon = idx("conilon"); val iDura = idx("dura"); val iRio = idx("rio")
        return lines.drop(1).mapNotNull { line ->
            val c = line.split(',')
            val coleta = runCatching { LocalDate.parse(c.getOrNull(iData)) }.getOrNull() ?: return@mapNotNull null
            val dia = c.getOrNull(iDia)?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
            // Se o dia do CCCV é maior que o dia da coleta, o preço é do mês anterior.
            val base = if (dia > coleta.dayOfMonth) coleta.minusMonths(1) else coleta
            val date = runCatching { base.withDayOfMonth(dia) }.getOrNull() ?: return@mapNotNull null
            fun d(i: Int) = c.getOrNull(i)?.toDoubleOrNull()
            CccvPoint(date, d(iDura), d(iRio), d(iCon))
        }
    }

    /** Boletim diário publicado pelo agente no GitHub. */
    fun boletim(): Boletim {
        val o = JSONObject(get("https://raw.githubusercontent.com/gustavosarti/cafe-analise-dados/main/boletim/boletim.json"))
        fun JSONObject?.s(k: String): String = this?.optString(k)?.takeIf { it != "null" }.orEmpty()
        val con = o.optJSONObject("conilon")
        val ara = o.optJSONObject("arabica")
        val fat = o.optJSONArray("fatores")
        val obs = o.optJSONArray("o_que_observar")
        val nots = o.optJSONArray("noticias")
        return Boletim(
            data = o.s("data"),
            geradoEm = o.s("gerado_em"),
            titulo = o.s("titulo"),
            resumo = o.s("resumo"),
            conilonTendencia = con.s("tendencia_futuro"),
            conilonVariacao = con.s("variacao_dia"),
            conilonFuturo = con.s("texto_futuro"),
            curtoPrazo = con.s("curto_prazo"),
            medioPrazo = con.s("medio_prazo"),
            arabicaTendencia = ara.s("tendencia_futuro"),
            arabicaTexto = ara.s("texto"),
            fatores = (0 until (fat?.length() ?: 0)).mapNotNull { i ->
                fat!!.optJSONObject(i)?.let { BoletimFator(it.s("fator"), it.s("efeito"), it.s("explicacao")) }
            },
            commodities = o.s("commodities"),
            oQueObservar = (0 until (obs?.length() ?: 0)).map { obs!!.optString(it) }.filter { it.isNotBlank() },
            noticias = (0 until (nots?.length() ?: 0)).mapNotNull { i ->
                nots!!.optJSONObject(i)?.let { BoletimNoticia(it.s("titulo"), it.s("resumo"), it.s("fonte"), it.s("url"), it.s("data")) }
            },
            aviso = o.s("aviso"),
        )
    }

    /** Busca cotação atual + histórico de um símbolo do Yahoo Finance. */
    fun yahooChart(symbol: String, range: String = "2y", interval: String = "1d"): Quote {
        val s = URLEncoder.encode(symbol, "UTF-8")
        val body = get("https://query1.finance.yahoo.com/v8/finance/chart/$s?range=$range&interval=$interval")
        val chart = JSONObject(body).getJSONObject("chart")
        val results = chart.optJSONArray("result")
        if (results == null || results.length() == 0) {
            val err = chart.optJSONObject("error")?.optString("description")
            throw IOException(if (err.isNullOrBlank()) "sem dados para $symbol" else err)
        }
        val r = results.getJSONObject(0)
        val meta = r.getJSONObject("meta")
        val zone = runCatching { ZoneId.of(meta.optString("exchangeTimezoneName")) }.getOrDefault(NY)

        val ts = r.optJSONArray("timestamp")
        val closes = r.optJSONObject("indicators")
            ?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")

        // Um ponto por dia (o Yahoo às vezes repete o último dia).
        val byDate = LinkedHashMap<LocalDate, Double>()
        if (ts != null && closes != null) {
            val n = minOf(ts.length(), closes.length())
            for (i in 0 until n) {
                if (closes.isNull(i)) continue
                val c = closes.optDouble(i)
                if (c.isNaN() || c <= 0.0) continue
                val d = Instant.ofEpochSecond(ts.getLong(i)).atZone(zone).toLocalDate()
                byDate[d] = c
            }
        }

        val metaPrice = meta.optDouble("regularMarketPrice")
        val price = if (!metaPrice.isNaN() && metaPrice > 0) metaPrice
        else byDate.values.lastOrNull() ?: throw IOException("sem preço para $symbol")

        val time = meta.optLong("regularMarketTime", 0L).let {
            if (it > 0) Instant.ofEpochSecond(it) else Instant.now()
        }
        val marketDate = time.atZone(zone).toLocalDate()

        // Fechamento anterior = último dia completo antes do pregão atual.
        val previous: Double? = byDate.entries.lastOrNull { it.key.isBefore(marketDate) }?.value

        // Garante que o último ponto do histórico seja o preço atual.
        byDate[marketDate] = price
        val history = byDate.entries.sortedBy { it.key }.map { PricePoint(it.key, it.value) }

        val name = meta.optString("shortName").ifBlank { symbol }
        return Quote(symbol, name, price, previous, time, history)
    }

    /** Dólar comercial. Tenta AwesomeAPI e, se falhar, Yahoo (BRL=X). */
    fun usdBrl(): FxRate = try {
        val o = JSONObject(get("https://economia.awesomeapi.com.br/json/last/USD-BRL")).getJSONObject("USDBRL")
        FxRate(o.getString("bid").toDouble(), o.optString("pctChange").toDoubleOrNull(), "AwesomeAPI")
    } catch (e: Exception) {
        val q = yahooChart("BRL=X", "1mo", "1d")
        FxRate(q.price, q.changePct, "Yahoo Finance")
    }

    /** Códigos dos vencimentos do café arábica na ICE: Mar(H), Mai(K), Jul(N), Set(U), Dez(Z). */
    private val ARABICA_MONTHS = mapOf(3 to 'H', 5 to 'K', 7 to 'N', 9 to 'U', 12 to 'Z')

    /** Próximos vencimentos do arábica, a partir do mês atual. */
    fun arabicaContracts(today: LocalDate, count: Int = 9): List<Pair<String, YearMonth>> {
        val out = mutableListOf<Pair<String, YearMonth>>()
        var ym = firstLiveMonth(today)
        while (out.size < count) {
            ARABICA_MONTHS[ym.monthValue]?.let { code ->
                out += "KC$code${"%02d".format(ym.year % 100)}.NYB" to ym
            }
            ym = ym.plusMonths(1)
        }
        return out
    }

    /** Cotação de um vencimento específico; retorna null se o contrato já venceu ou não tem negócios. */
    fun contract(symbol: String, expiry: YearMonth): CurvePoint? {
        val q = yahooChart(symbol, "5d", "1d")
        val age = Duration.between(q.time, Instant.now())
        if (age.toDays() > 7) return null
        return CurvePoint(symbol, expiry, q.price)
    }
}
