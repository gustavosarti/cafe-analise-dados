package br.com.cafeanalise.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cafe_analise", Context.MODE_PRIVATE)

    private fun d(key: String, def: Double) = sp.getString(key, null)?.toDoubleOrNull() ?: def

    fun load(): Settings {
        val def = Settings()
        return Settings(
            arabicaSymbol = sp.getString("arabicaSymbol", null) ?: def.arabicaSymbol,
            // "RC=F" era o padrão antigo e não existe no Yahoo: migra para o novo padrão.
            robustaSymbol = sp.getString("robustaSymbol", null)
                ?.takeIf { it.isNotBlank() && it != "RC=F" } ?: def.robustaSymbol,
            basisArabica = d("basisArabica", def.basisArabica),
            basisRobusta = d("basisRobusta", def.basisRobusta),
            sacas = d("sacas", def.sacas),
            custoSaca = d("custoSaca", def.custoSaca),
            calcProduct = runCatching { CccvProduct.valueOf(sp.getString("calcProduct", null) ?: "") }
                .getOrDefault(def.calcProduct),
        )
    }

    fun save(s: Settings) {
        sp.edit()
            .putString("arabicaSymbol", s.arabicaSymbol)
            .putString("robustaSymbol", s.robustaSymbol)
            .putString("basisArabica", s.basisArabica.toString())
            .putString("basisRobusta", s.basisRobusta.toString())
            .putString("sacas", s.sacas.toString())
            .putString("custoSaca", s.custoSaca.toString())
            .putString("calcProduct", s.calcProduct.name)
            .apply()
    }
}
