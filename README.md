# Café Análise — dados

Dados de mercado de café usados pelo app **Café Análise** (Android).

| Arquivo | O que é | Quem gera |
|---|---|---|
| `dados/mercado.json` | Preços atuais: CCCV (Vitória/ES), robusta (ICE Londres), arábica (ICE NY), dólar, outras commodities e a comparação "hoje × futuro" | Coletor automático (`coletor/coletar.py`, GitHub Actions) |
| `dados/historico.csv` | Uma linha por dia com os principais preços | Coletor automático |
| `boletim/boletim.json` | Boletim diário em linguagem simples: resumo, hoje × futuro, fatores de alta/baixa e notícias | Agente diário (Claude), seguindo `AGENTE.md` |

Os números vêm sempre das fontes (CCCV, TradingView, Yahoo Finance, AwesomeAPI). O agente só interpreta e
pesquisa notícias. Conteúdo informativo, não é recomendação de compra ou venda.
