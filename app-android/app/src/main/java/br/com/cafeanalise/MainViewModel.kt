package br.com.cafeanalise

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.cafeanalise.data.Boletim
import br.com.cafeanalise.data.CccvPoint
import br.com.cafeanalise.data.CccvQuote
import br.com.cafeanalise.data.CurvePoint
import br.com.cafeanalise.data.FxRate
import br.com.cafeanalise.data.Market
import br.com.cafeanalise.data.MarketApi
import br.com.cafeanalise.data.Prefs
import br.com.cafeanalise.data.Quote
import br.com.cafeanalise.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class UiState(
    val loading: Boolean = false,
    val quotes: Map<Market, Result<Quote>> = emptyMap(),
    val fx: Result<FxRate>? = null,
    val cccv: Result<CccvQuote>? = null,
    val boletim: Result<Boletim>? = null,
    /** Histórico do CCCV salvo pelo coletor (cresce um dia por vez). */
    val historico: List<CccvPoint> = emptyList(),
    /** Curva de futuros do arábica (NY, US¢/lb). */
    val curve: List<CurvePoint> = emptyList(),
    /** Curva de futuros da robusta (Londres, US$/t). */
    val robustaCurve: List<CurvePoint> = emptyList(),
    val curveLoading: Boolean = false,
    val updatedAt: LocalDateTime? = null,
) {
    fun quote(m: Market): Quote? = quotes[m]?.getOrNull()
    val usdBrl: Double? get() = fx?.getOrNull()?.bid
    /**
     * Vencimentos futuros para comparar com hoje. O primeiro contrato costuma ser o próprio "contínuo"
     * (a referência do preço de hoje, ex.: Jan/27 na robusta); ele não diz nada sobre o futuro e é descartado.
     */
    fun curve(m: Market): List<CurvePoint> {
        val raw = if (m == Market.ARABICA) curve else robustaCurve
        val ref = quote(m)?.price ?: return raw
        val first = raw.firstOrNull() ?: return raw
        return if (kotlin.math.abs(first.price / ref - 1) < 0.005) raw.drop(1) else raw
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    private val _settings = MutableStateFlow(prefs.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun updateSettings(s: Settings) {
        val old = _settings.value
        _settings.value = s
        prefs.save(s)
        if (s.arabicaSymbol != old.arabicaSymbol || s.robustaSymbol != old.robustaSymbol) refresh()
    }

    private var lastCurveAt = 0L

    /** Atualiza se os dados tiverem mais de [maxAgeSeconds] segundos. */
    fun refreshIfStale(maxAgeSeconds: Long = 30) {
        val at = _state.value.updatedAt
        if (at == null || java.time.Duration.between(at, LocalDateTime.now()).seconds >= maxAgeSeconds) refresh()
    }

    /**
     * Cotações, dólar e CCCV sempre; a curva de futuros (9 consultas) só a cada 10 min
     * ou quando o usuário toca no botão de atualizar ([force]).
     */
    fun refresh(force: Boolean = false) {
        if (_state.value.loading) return
        val s = _settings.value
        val now = System.currentTimeMillis()
        val emptyCurve = _state.value.curve.isEmpty() || _state.value.robustaCurve.isEmpty()
        val loadCurve = force || (emptyCurve && now - lastCurveAt > 2 * 60_000) || now - lastCurveAt > 10 * 60_000
        _state.update { it.copy(loading = true, curveLoading = loadCurve || it.curveLoading) }
        viewModelScope.launch {
            coroutineScope {
                val a = async(Dispatchers.IO) { runCatching { MarketApi.quote(s.arabicaSymbol.trim()) } }
                val r = async(Dispatchers.IO) { runCatching { MarketApi.quote(s.robustaSymbol.trim()) } }
                val f = async(Dispatchers.IO) { runCatching { MarketApi.usdBrl() } }
                val c = async(Dispatchers.IO) { runCatching { MarketApi.cccv() } }
                val bol = async(Dispatchers.IO) { runCatching { MarketApi.boletim() } }
                val hist = async(Dispatchers.IO) { runCatching { MarketApi.historico() }.getOrDefault(emptyList()) }
                val arabica = a.await()
                val robusta = r.await()
                val fx = f.await()
                val cccv = c.await()
                val boletim = bol.await()
                val historico = hist.await()
                _state.update {
                    it.copy(
                        loading = false,
                        quotes = mapOf(Market.ARABICA to arabica, Market.ROBUSTA to robusta),
                        fx = fx,
                        // Se o CCCV falhar (site lento), mantém o último preço carregado.
                        cccv = if (cccv.isSuccess || it.cccv?.isSuccess != true) cccv else it.cccv,
                        // Se falhar, mantém o boletim anterior.
                        boletim = if (boletim.isSuccess || it.boletim == null) boletim else it.boletim,
                        historico = historico.ifEmpty { it.historico },
                        updatedAt = LocalDateTime.now(),
                    )
                }
            }

            if (!loadCurve) return@launch
            lastCurveAt = now
            val (curve, robCurve) = coroutineScope {
                val rob = async(Dispatchers.IO) { runCatching { MarketApi.robustaCurve(LocalDate.now()) }.getOrDefault(emptyList()) }
                val ara = MarketApi.arabicaContracts(LocalDate.now()).map { (sym, ym) ->
                    async(Dispatchers.IO) { runCatching { MarketApi.contract(sym, ym) }.getOrNull() }
                }.awaitAll().filterNotNull()
                ara to rob.await()
            }
            _state.update { st ->
                // Se a consulta falhar, mantém a curva anterior.
                st.copy(
                    curve = if (curve.isEmpty()) st.curve else curve.sortedBy { it.expiry }.take(8),
                    robustaCurve = if (robCurve.isEmpty()) st.robustaCurve else robCurve.sortedBy { it.expiry }.take(8),
                    curveLoading = false,
                )
            }
        }
    }
}
