package br.com.cafeanalise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.cafeanalise.ui.AppIcons
import br.com.cafeanalise.ui.Bg
import br.com.cafeanalise.ui.BoletimScreen
import br.com.cafeanalise.ui.CafeTheme
import br.com.cafeanalise.ui.CalcScreen
import br.com.cafeanalise.ui.CardBg
import br.com.cafeanalise.ui.ChartScreen
import br.com.cafeanalise.ui.ForecastScreen
import br.com.cafeanalise.ui.FuturesScreen
import br.com.cafeanalise.ui.Gold
import br.com.cafeanalise.ui.HomeScreen
import br.com.cafeanalise.ui.Muted
import br.com.cafeanalise.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CafeTheme { App() } }
    }
}

enum class Tab(val label: String) {
    HOJE("Hoje"),
    GRAFICO("Gráfico"),
    FUTUROS("Futuros"),
    PROJECAO("Tendência"),
    CALC("Calcular");

    val icon: ImageVector
        get() = when (this) {
            HOJE -> AppIcons.Home
            GRAFICO -> AppIcons.Chart
            FUTUROS -> AppIcons.Calendar
            PROJECAO -> AppIcons.Trending
            CALC -> AppIcons.Calculate
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOJE) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showBoletim by rememberSaveable { mutableStateOf(false) }
    val boletim = state.boletim?.getOrNull()?.takeIf { it.disponivel }
    val overlay = showSettings || (showBoletim && boletim != null)

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && showBoletim) { showBoletim = false }

    // Atualização automática: ao abrir/voltar para o app e a cada 60 s enquanto ele estiver na tela.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshIfStale(15)
            while (true) {
                delay(60_000)
                vm.refresh()
            }
        }
    }
    // Ao voltar para a aba Hoje, atualiza se os dados tiverem mais de 30 s.
    LaunchedEffect(tab, showSettings) {
        if (tab == Tab.HOJE && !showSettings) vm.refreshIfStale(30)
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            showSettings -> "Configurações"
                            showBoletim && boletim != null -> "Boletim do dia"
                            else -> "Café Análise"
                        },
                        fontWeight = FontWeight.Bold,
                        color = if (overlay) Color.White else Gold,
                    )
                },
                navigationIcon = {
                    if (overlay) IconButton(onClick = { if (showSettings) showSettings = false else showBoletim = false }) {
                        Icon(AppIcons.Back, contentDescription = "Voltar", tint = Gold)
                    }
                },
                actions = {
                    if (!overlay) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), color = Gold, strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { vm.refresh(force = true) }) {
                                Icon(AppIcons.Refresh, contentDescription = "Atualizar", tint = Gold)
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(AppIcons.Settings, contentDescription = "Configurações", tint = Gold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        },
        bottomBar = {
            if (!overlay) {
                NavigationBar(containerColor = CardBg) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(24.dp)) },
                            label = { Text(t.label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = Gold,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted,
                                indicatorColor = Gold,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (showSettings) {
                SettingsScreen(settings, vm::updateSettings) { showSettings = false }
            } else if (showBoletim && boletim != null) {
                BoletimScreen(boletim)
            } else {
                when (tab) {
                    Tab.HOJE -> HomeScreen(state, settings) { showBoletim = true }
                    Tab.GRAFICO -> ChartScreen(state)
                    Tab.FUTUROS -> FuturesScreen(state, settings)
                    Tab.PROJECAO -> ForecastScreen(state, settings)
                    Tab.CALC -> CalcScreen(state, settings, vm::updateSettings)
                }
            }
        }
    }
}
