# Café Análise

App Android e dados de mercado de café com **foco no Espírito Santo**: preços físicos do **CCCV (Vitória)**,
principalmente do **conilon**, comparados com as bolsas de Londres e Nova York.

- `app-android/` — código do app (veja o README da pasta)
- `dados/` e `boletim/` — dados de mercado e boletim diário lidos pelo app

| Arquivo | O que é | Quem gera |
|---|---|---|
| `dados/mercado.json` | Preços atuais: CCCV (Vitória/ES), robusta (ICE Londres), arábica (ICE NY), dólar, outras commodities e a comparação "hoje × futuro" | Coletor automático (`coletor/coletar.py`, GitHub Actions) |
| `dados/historico.csv` | Uma linha por dia com os principais preços | Coletor automático |
| `boletim/boletim.json` | Boletim diário em linguagem simples: resumo, hoje × futuro, fatores de alta/baixa e notícias | Agente diário (Claude), seguindo `AGENTE.md` |

Os números vêm sempre das fontes (CCCV, TradingView, Yahoo Finance, AwesomeAPI). O agente só interpreta e
pesquisa notícias. Conteúdo informativo, não é recomendação de compra ou venda.
