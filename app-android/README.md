# Café Análise — app Android (foco no Espírito Santo)

App para produtores de café do **Espírito Santo**, principalmente de **conilon**, que vendem no
**CCCV (Centro do Comércio de Café de Vitória)**. Mostra o preço do dia no CCCV, as bolsas de Londres
(robusta) e Nova York (arábica), e compara sempre o **preço de hoje com o mercado futuro**, em linguagem
simples para quem não acompanha bolsa.

## Telas

| Aba | O que mostra |
|---|---|
| **Hoje** | Resumo do conilon no CCCV (preço, variação, valor das sacas e "o que o mercado espera" — expectativa do mundo e do Espírito Santo escrita pelo agente de IA), boletim do dia, cotações do CCCV (arábica dura, rio e conilon 7/8) e as bolsas de referência. Atualiza sozinho a cada minuto. |
| **Gráfico** | Histórico do arábica (NY) com médias móveis. |
| **Futuros** | "Hoje × futuro": preço de hoje no CCCV e a estimativa de cada vencimento (Londres para conilon, NY para arábica), com pontos acima/abaixo de hoje. |
| **Tendência** | Para onde o mercado aponta, desempenho da robusta, indicadores técnicos e o conilon no CCCV ao longo do mês. |
| **Calcular** | Valor da venda no CCCV pela quantidade de sacas, e quanto daria travando em cada vencimento futuro. |
| **Boletim do dia** | Análise diária escrita por um agente de IA (notícias, fatores de alta e baixa, cenários). Vem de `boletim/boletim.json` deste repositório. |

## Como o preço do CCCV é projetado

`CCCV estimado = vencimento da bolsa convertido em R$/saca + diferencial de hoje (CCCV − bolsa)`,
usando o dólar do dia. Conversões: 1 saca = 60 kg = 132,277 lb; robusta US$/t × 0,06 = US$/saca.

## Fontes de dados (gratuitas, sem cadastro)

- **CCCV** — https://www.cccv.org.br/cotacao/ (físico em Vitória, R$/saca)
- **TradingView** — robusta ICE Londres (contínuo e vencimentos) e indicadores
- **Yahoo Finance** — arábica ICE Nova York (contínuo e vencimentos)
- **AwesomeAPI** — dólar comercial
- **Boletim diário** — `boletim/boletim.json` deste repositório

## Como compilar

Abra esta pasta (`app-android`) no Android Studio e use **Build › Generate APKs** (ou *Assemble*).
Requisitos: Android 7.0 (API 24) ou superior.

```
app/src/main/java/br/com/cafeanalise/
├── MainActivity.kt        navegação, abas e atualização automática
├── MainViewModel.kt       carrega cotações, CCCV, curvas e boletim em paralelo
├── data/                  MarketApi (HTTP/JSON e leitura do CCCV), modelos, preferências
├── analysis/Analytics.kt  médias, RSI, volatilidade, sinais
├── ui/                    telas, gráficos (Canvas) e ícones
└── util/Format.kt         formatação pt-BR
```

*Informações de apoio à decisão; não são recomendação de compra ou venda.*
