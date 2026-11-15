package com.khancer.tiermaker

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts

/** Configurações e personalização do aplicativo. */
class SettingsActivity : BaseActivity() {

    private val pickWall =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importWallpaper(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val box = vbox()
        box.setPadding(dp(16), dp(24), dp(16), dp(24))

        box.addView(label("Configurações", 28f, true, true))

        box.addView(section("Cores do aplicativo"))
        box.addView(colorRow("Cor de fundo", { AppSettings.bgColor }, true) {
            AppSettings.bgColor = it
            recreate()
        })
        box.addView(colorRow("Painéis e barras", { AppSettings.panelColor }, true) {
            AppSettings.panelColor = it
            recreate()
        })
        box.addView(colorRow("Cor de destaque (botões)", { AppSettings.accent }, true) {
            AppSettings.accent = it
            recreate()
        })
        box.addView(colorRow("Cor do texto", { AppSettings.textColor }, true) {
            AppSettings.textColor = it
            recreate()
        })

        box.addView(section("Papel de parede"))
        val wallRow = hbox()
        wallRow.addView(button("Escolher imagem") { pickWall.launch("image/*") })
        wallRow.addView(button("Remover") {
            AppSettings.setWallpaper(null)
            recreate()
        })
        box.addView(wallRow)
        addSeek(box, "Escurecer papel de parede", "%", 0, 80, AppSettings.wallpaperDim, false, true) {
            AppSettings.wallpaperDim = it
            recreate()
        }

        box.addView(section("Listas animadas (GIF)"))
        val animCheck = CheckBox(this)
        animCheck.text = "Permitir GIFs animados nas listas"
        animCheck.setTextColor(AppSettings.textColor)
        animCheck.isChecked = AppSettings.animEnabled
        animCheck.setOnCheckedChangeListener { _, c -> AppSettings.animEnabled = c }
        box.addView(animCheck)
        addSeek(box, "Quadros por segundo", "fps", 6, 24, AppSettings.animFps, false, true) {
            AppSettings.animFps = it
        }
        val info = label(
            "Com a opção ligada, os GIFs que você adicionar continuam animados, tanto como " +
                "ícones quanto como imagem do tier. Todos rodam no mesmo ciclo: ele tem a duração " +
                "do GIF mais longo da lista, e cada GIF tem a velocidade ajustada de leve para " +
                "repetir um número exato de vezes e terminar junto com os outros, então nenhum " +
                "congela esperando o outro. Ao salvar, a lista vira um GIF animado com esse ciclo. " +
                "GIFs adicionados com a opção desligada ficam parados (primeiro quadro).",
            13f, false, true
        )
        info.alpha = 0.7f
        info.setPadding(0, dp(8), 0, 0)
        box.addView(info)

        box.addView(section("Geral"))
        box.addView(button("Restaurar padrões do app") {
            AlertDialog.Builder(this)
                .setTitle("Restaurar padrões?")
                .setMessage("Cores, papel de parede e opções de animação voltam ao original. Suas listas não são afetadas.")
                .setPositiveButton("Restaurar") { _, _ ->
                    AppSettings.reset()
                    recreate()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })

        val sv = ScrollView(this)
        sv.addView(box, LinearLayout.LayoutParams(MATCH, WRAP))
        setPage(sv)
    }

    private fun importWallpaper(uri: Uri) {
        io.execute {
            try {
                val bmp = decodeFit(uri, 1600)
                AppSettings.setWallpaper(bmp)
                ui.post { recreate() }
            } catch (e: Exception) {
                ui.post { toast("Não foi possível usar essa imagem") }
            }
        }
    }
}
