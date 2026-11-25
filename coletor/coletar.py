#!/usr/bin/env python3
"""
Coletor de dados de mercado de café para o app Café Análise.

Roda no GitHub Actions (sem IA): busca os números direto das fontes e grava
  dados/mercado.json   -> fotografia atual do mercado
  dados/historico.csv  -> uma linha por dia (vai formando o histórico)

Fontes (gratuitas, sem chave):
  - CCCV (Vitória/ES): https://www.cccv.org.br/cotacao/  (físico, R$/saca)
  - TradingView scanner: robusta ICE Londres (contínuo + vencimentos), indicadores
  - TradingView: também arábica ICE NY, outras commodities e dólar
  - AwesomeAPI / Banco Central (PTAX): reservas para o dólar
Só usa a biblioteca padrão do Python.
"""
import csv
import datetime as dt
import html
import json
import os
import re
import sys
import urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
LB_POR_SACA = 132.277
TZ_BR = dt.timezone(dt.timedelta(hours=-3))
RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MESES = ["Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez"]
ERROS = []


def http(url, data=None, headers=None, timeout=25):
    h = {"User-Agent": UA, "Accept": "*/*"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", errors="replace")


def seguro(nome, fn, padrao=None):
    try:
        return fn()
    except Exception as e:  # noqa: BLE001
        ERROS.append(f"{nome}: {type(e).__name__}: {e}")
        print(f"[ERRO] {nome}: {e}", file=sys.stderr)
        return padrao


def rotulo(ano, mes):
    return f"{MESES[mes - 1]}/{ano % 100:02d}"


def primeiro_mes_vivo(hoje):
    """Exclui o mês atual e, a partir do dia 20, o seguinte (contratos em entrega)."""
    m = hoje.month + (2 if hoje.day >= 20 else 1)
    a = hoje.year + (m - 1) // 12
    return a, (m - 1) % 12 + 1


def proximos(hoje, meses_validos, n):
    a, m = primeiro_mes_vivo(hoje)
    out = []
    while len(out) < n:
        if m in meses_validos:
            out.append((a, m))
        m += 1
        if m > 12:
            m, a = 1, a + 1
    return out


# ---------------------------------------------------------------- CCCV
def cccv():
    pagina = http("https://www.cccv.org.br/cotacao/", headers={"Accept": "text/html"})

    def limpa(s):
        s = re.sub(r"<[^>]+>", " ", s)
        return re.sub(r"\s+", " ", html.unescape(s)).strip()

    def num(v):
        v = v.strip()
        if v in ("", "-"):
            return None
        try:
            return float(v.replace(".", "").replace(",", "."))
        except ValueError:
            return None

    titulo = re.search(r"refer\S*\s+ao\s+m\S*s\s+de\s+(\w+)\s+de\s+(\d{4})", limpa(pagina), re.I)
    dias, media = [], None
    for linha in re.findall(r"<tr[^>]*>(.*?)</tr>", pagina, re.S | re.I):
        cels = [limpa(c) for c in re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", linha, re.S | re.I)]
        cels = [c for c in cels if c]
        if len(cels) < 4:
            continue
        vals = [num(c) for c in cels[1:4]]
        if re.fullmatch(r"\d{1,2}", cels[0]):
            if any(v is not None for v in vals):
                dias.append({"dia": int(cels[0]), "dura": vals[0], "rio": vals[1], "conilon": vals[2]})
        elif cels[0].lower().startswith("m") and "dia" in cels[0].lower():
            media = {"dura": vals[0], "rio": vals[1], "conilon": vals[2]}
    if not dias:
        raise RuntimeError("tabela do CCCV não encontrada")
    dias.sort(key=lambda d: d["dia"])
    return {
        "mes": titulo.group(1).lower() if titulo else None,
        "ano": int(titulo.group(2)) if titulo else None,
        "unidade": "R$/saca 60 kg",
        "ultimo": dias[-1],
        "anterior": dias[-2] if len(dias) > 1 else None,
        "dias": dias,
        "media_mes": media,
    }


# ---------------------------------------------------------------- TradingView
def tv_scan(tickers, colunas, mercados=("futures", "global")):
    corpo = json.dumps({"symbols": {"tickers": tickers, "query": {"types": []}}, "columns": colunas}).encode()
    hdr = {"Content-Type": "application/json", "Origin": "https://www.tradingview.com", "Referer": "https://www.tradingview.com/"}
    ultimo = None
    for mercado in mercados:
        try:
            dados = json.loads(http(f"https://scanner.tradingview.com/{mercado}/scan", corpo, hdr)).get("data") or []
            if dados:
                return {d["s"]: dict(zip(colunas, d["d"])) for d in dados}
        except Exception as e:  # noqa: BLE001
            ultimo = e
    raise RuntimeError(f"TradingView sem dados para {tickers[:2]}… ({ultimo})")


def robusta(hoje):
    cols = ["close", "change_abs", "change", "SMA20", "SMA50", "SMA200", "RSI", "Perf.W", "Perf.1M",
            "Perf.3M", "Perf.6M", "Perf.Y", "price_52_week_high", "price_52_week_low", "Recommend.All"]
    try:
        cont = tv_scan(["ICEEUR:RC1!"], cols)["ICEEUR:RC1!"]
    except Exception:  # noqa: BLE001 - colunas extras podem falhar; tenta o básico
        cont = tv_scan(["ICEEUR:RC1!"], cols[:7])["ICEEUR:RC1!"]
    codigos = {1: "F", 3: "H", 5: "K", 7: "N", 9: "U", 11: "X"}
    venc = proximos(hoje, codigos, 8)
    tick = [f"ICEEUR:RC{codigos[m]}{a}" for a, m in venc]
    curva_raw = seguro("curva robusta", lambda: tv_scan(tick, ["close"]), {})
    curva = [
        {"vencimento": rotulo(a, m), "ano": a, "mes": m, "preco": curva_raw[t]["close"]}
        for (a, m), t in zip(venc, tick)
        if t in curva_raw and curva_raw[t].get("close")
    ]
    return {"unidade": "US$/t", "bolsa": "ICE Londres", "continuo": cont, "curva": curva}


TV_COLS = ["close", "change", "change_abs", "description", "Perf.W", "Perf.1M", "Perf.3M", "Perf.Y",
           "price_52_week_high", "price_52_week_low", "SMA50", "SMA200", "RSI", "Recommend.All"]


def tv_quote(ticker, mercados=("futures", "global")):
    """Cotação no mesmo formato do Yahoo, a partir do TradingView."""
    try:
        d = tv_scan([ticker], TV_COLS, mercados)[ticker]
    except Exception:  # noqa: BLE001 - se alguma coluna extra falhar, tenta o básico
        d = tv_scan([ticker], TV_COLS[:4], mercados)[ticker]
    return {
        "simbolo": ticker,
        "nome": d.get("description"),
        "preco": d.get("close"),
        "var_dia_pct": d.get("change"),
        "var_semana_pct": d.get("Perf.W"),
        "var_mes_pct": d.get("Perf.1M"),
        "var_3m_pct": d.get("Perf.3M"),
        "var_ano_pct": d.get("Perf.Y"),
        "max_52s": d.get("price_52_week_high"),
        "min_52s": d.get("price_52_week_low"),
        "sma50": d.get("SMA50"),
        "sma200": d.get("SMA200"),
        "rsi": d.get("RSI"),
        "sinal_tecnico": d.get("Recommend.All"),
        "fonte": "TradingView",
    }


# ---------------------------------------------------------------- Yahoo (reserva; bloqueia servidores do GitHub com frequência)
def yahoo(simbolo, rng="1y", intervalo="1d"):
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.request.quote(simbolo)}?range={rng}&interval={intervalo}"
    r = json.loads(http(url, headers={"Accept": "application/json"}))["chart"]["result"][0]
    meta = r["meta"]
    fech = [c for c in (r.get("indicators", {}).get("quote", [{}])[0].get("close") or []) if c]
    preco = meta.get("regularMarketPrice") or (fech[-1] if fech else None)

    def perf(n):
        return round((preco / fech[-1 - n] - 1) * 100, 2) if preco and len(fech) > n else None

    return {
        "simbolo": simbolo,
        "nome": meta.get("shortName"),
        "preco": preco,
        "moeda": meta.get("currency"),
        "var_dia_pct": perf(1),
        "var_semana_pct": perf(5),
        "var_mes_pct": perf(21),
        "var_3m_pct": perf(63),
        "var_ano_pct": perf(250),
        "max_52s": max(fech) if fech else None,
        "min_52s": min(fech) if fech else None,
        "atualizado": dt.datetime.fromtimestamp(meta.get("regularMarketTime", 0), dt.timezone.utc).isoformat(),
    }


def arabica(hoje):
    cont = tv_quote("ICEUS:KC1!")
    codigos = {3: "H", 5: "K", 7: "N", 9: "U", 12: "Z"}
    venc = proximos(hoje, codigos, 8)
    tick = [f"ICEUS:KC{codigos[m]}{a}" for a, m in venc]
    curva_raw = seguro("curva arábica", lambda: tv_scan(tick, ["close"]), {})
    curva = [
        {"vencimento": rotulo(a, m), "ano": a, "mes": m, "preco": curva_raw[t]["close"]}
        for (a, m), t in zip(venc, tick)
        if t in curva_raw and curva_raw[t].get("close")
    ]
    return {"unidade": "US¢/lb", "bolsa": "ICE Nova York", "continuo": cont, "curva": curva}


def dolar():
    """Dólar comercial: TradingView; reservas: AwesomeAPI e PTAX do Banco Central."""
    try:
        q = tv_quote("FX_IDC:USDBRL", ("forex", "global"))
        if q.get("preco"):
            return {"cotacao": q["preco"], "var_dia_pct": q["var_dia_pct"], "var_mes_pct": q["var_mes_pct"], "fonte": "TradingView"}
    except Exception as e:  # noqa: BLE001
        ERROS.append(f"dólar TradingView: {e}")
    try:
        o = json.loads(http("https://economia.awesomeapi.com.br/json/last/USD-BRL"))["USDBRL"]
        return {"cotacao": float(o["bid"]), "var_dia_pct": float(o["pctChange"]), "fonte": "AwesomeAPI"}
    except Exception as e:  # noqa: BLE001
        ERROS.append(f"dólar AwesomeAPI: {e}")
    hoje = dt.date.today()
    ini = (hoje - dt.timedelta(days=7)).strftime("%m-%d-%Y")
    fim = hoje.strftime("%m-%d-%Y")
    url = ("https://olinda.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata/CotacaoDolarPeriodo(dataInicial=@i,dataFinalCotacao=@f)"
           f"?@i='{ini}'&@f='{fim}'&$format=json&$select=cotacaoVenda,dataHoraCotacao")
    v = json.loads(http(url))["value"]
    return {"cotacao": v[-1]["cotacaoVenda"], "var_dia_pct": None, "fonte": "Banco Central (PTAX)"}


OUTRAS = {
    "acucar": ("ICEUS:SB1!", "Açúcar (NY)", "US¢/lb"),
    "cacau": ("ICEUS:CC1!", "Cacau (NY)", "US$/t"),
    "milho": ("CBOT:ZC1!", "Milho (Chicago)", "US¢/bushel"),
    "soja": ("CBOT:ZS1!", "Soja (Chicago)", "US¢/bushel"),
    "petroleo_brent": ("ICEEUR:BRN1!", "Petróleo Brent", "US$/barril"),
    "indice_dolar": ("TVC:DXY", "Índice do dólar (DXY)", "pontos"),
}


# ---------------------------------------------------------------- cálculos
def por_saca_usd(mercado, preco):
    return preco / 100 * LB_POR_SACA if mercado == "arabica" else preco * 0.06


def comparar_hoje_futuro(mercado, bolsa, cccv_hoje, usd):
    """Converte cada vencimento para R$/saca no CCCV mantendo o diferencial de hoje."""
    if not bolsa or not bolsa.get("curva"):
        return None
    cont = bolsa["continuo"]
    preco_cont = cont.get("close") if "close" in cont else cont.get("preco")
    out = {"hoje_bolsa": preco_cont, "unidade_bolsa": bolsa["unidade"], "hoje_cccv": cccv_hoje, "vencimentos": []}
    base = None
    if cccv_hoje and usd and preco_cont:
        base = cccv_hoje - por_saca_usd(mercado, preco_cont) * usd
        out["diferencial_cccv_rs_saca"] = round(base, 2)
    curva = list(bolsa["curva"])
    # O primeiro contrato costuma ser o próprio "contínuo" (a referência do preço de hoje):
    # ele não diz nada sobre o futuro, então sai da comparação.
    if curva and preco_cont and abs(curva[0]["preco"] / preco_cont - 1) < 0.005:
        out["contrato_referencia"] = curva[0]["vencimento"]
        curva = curva[1:]
    for c in curva:
        item = {"vencimento": c["vencimento"], "preco_bolsa": c["preco"],
                "vs_hoje_bolsa_pct": round((c["preco"] / preco_cont - 1) * 100, 2) if preco_cont else None}
        if base is not None:
            est = por_saca_usd(mercado, c["preco"]) * usd + base
            item["cccv_estimado"] = round(est, 2)
            item["vs_hoje_cccv_pct"] = round((est / cccv_hoje - 1) * 100, 2)
        out["vencimentos"].append(item)
    v = [i.get("vs_hoje_cccv_pct", i["vs_hoje_bolsa_pct"]) for i in out["vencimentos"] if i.get("vs_hoje_bolsa_pct") is not None]
    if v:
        acima = sum(1 for x in v if x > 0.1)
        abaixo = sum(1 for x in v if x < -0.1)
        out["resumo"] = {"acima_de_hoje": acima, "abaixo_de_hoje": abaixo, "maior_pct": max(v), "menor_pct": min(v),
                         "leitura": "alta" if acima > abaixo else "queda" if abaixo > acima else "estavel"}
    return out


def main():
    agora = dt.datetime.now(TZ_BR)
    hoje = agora.date()
    dados = {"gerado_em": agora.isoformat(timespec="seconds")}

    dados["cccv"] = seguro("CCCV", cccv)
    dados["dolar"] = seguro("dólar", dolar)
    dados["robusta"] = seguro("robusta", lambda: robusta(hoje))
    dados["arabica"] = seguro("arábica", lambda: arabica(hoje))
    dados["outras_commodities"] = {
        k: dict(seguro(nome, lambda s=s: tv_quote(s, ("futures", "global", "cfd"))) or {}, nome=nome, unidade=u)
        for k, (s, nome, u) in OUTRAS.items()
    }

    usd = (dados["dolar"] or {}).get("cotacao")
    ult = (dados["cccv"] or {}).get("ultimo") or {}
    dados["hoje_x_futuro"] = {
        "conilon": seguro("comparação conilon", lambda: comparar_hoje_futuro("robusta", dados["robusta"], ult.get("conilon"), usd)),
        "arabica_dura": seguro("comparação arábica", lambda: comparar_hoje_futuro("arabica", dados["arabica"], ult.get("dura"), usd)),
    }
    dados["erros"] = ERROS

    os.makedirs(os.path.join(RAIZ, "dados"), exist_ok=True)
    with open(os.path.join(RAIZ, "dados", "mercado.json"), "w", encoding="utf-8") as f:
        json.dump(dados, f, ensure_ascii=False, indent=2)

    # histórico: uma linha por dia (a última coleta do dia sobrescreve a anterior)
    caminho = os.path.join(RAIZ, "dados", "historico.csv")
    campos = ["data", "cccv_dia", "conilon", "dura", "rio", "robusta_usd_t", "arabica_usc_lb", "usd_brl"]
    linhas = []
    if os.path.exists(caminho):
        with open(caminho, encoding="utf-8") as f:
            linhas = [r for r in csv.DictReader(f) if r["data"] != hoje.isoformat()]
    rob = ((dados["robusta"] or {}).get("continuo") or {}).get("close")
    ara = ((dados["arabica"] or {}).get("continuo") or {}).get("preco")
    linhas.append({"data": hoje.isoformat(), "cccv_dia": ult.get("dia"), "conilon": ult.get("conilon"), "dura": ult.get("dura"),
                   "rio": ult.get("rio"), "robusta_usd_t": rob, "arabica_usc_lb": ara, "usd_brl": usd})
    with open(caminho, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=campos)
        w.writeheader()
        w.writerows(linhas)

    print(json.dumps({"ok": not ERROS, "erros": ERROS}, ensure_ascii=False))
    # Falha o job só se não conseguiu nada do essencial.
    if not dados["cccv"] and not dados["robusta"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
