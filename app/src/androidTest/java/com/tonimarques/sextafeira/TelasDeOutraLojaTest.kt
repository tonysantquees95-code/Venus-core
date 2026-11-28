package com.tonimarques.sextafeira

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonimarques.sextafeira.robo.TelaRespostas
import com.tonimarques.sextafeira.robo.TelaRobo
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Só no emulador de teste: cadastra uma loja de roupas fictícia e fotografa as telas que abrem sem login. */
@RunWith(AndroidJUnit4::class)
class TelasDeOutraLojaTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val pasta = File(ctx.filesDir, "telas").apply { mkdirs() }

    private fun rolagem(v: View): ScrollView? =
        if (v is ScrollView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { rolagem(g.getChildAt(it)) } }

    private fun foto(nome: String) {
        val b = inst.uiAutomation.takeScreenshot()
        File(pasta, "$nome.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun fotografar(classe: Class<*>, nome: String) {
        val tela = inst.startActivitySync(Intent(ctx, classe).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(6000)
        for (i in 1..8) {
            foto("$nome-$i")
            var continua = false
            inst.runOnMainSync {
                val r = rolagem(tela.window.decorView)
                if (r != null && r.canScrollVertically(1)) { r.scrollBy(0, (r.height * 0.85f).toInt()); continua = true }
            }
            if (!continua) break
            SystemClock.sleep(800)
        }
        inst.runOnMainSync { tela.finish() }
        SystemClock.sleep(1000)
    }

    @Test
    fun lojaDeRoupasSemSite() {
        inst.runOnMainSync {
            Perfil.carregar(ctx)
            Perfil.salvar(ctx, "Ana", "Ana Modas", "Curitiba PR", "")
            Perfil.salvarLoja(ctx, "", "roupas", RegrasDeVenda.VAZIA)
        }
        val menu = AtendenteDoRobo.menu(Perfil.loja, "Vênus", Perfil.regras.retiradaDisponivel, Perfil.entregaDiscreta)
        File(pasta, "relatorio.txt").writeText(
            "loja=${Perfil.loja} | ramo=${Perfil.ramo} | site='${Perfil.site}' | primeiraLoja=${Perfil.primeiraLoja} | ramoIntimo=${Perfil.ramoIntimo} | entregaDiscreta=${Perfil.entregaDiscreta}\n" +
                "storiesLigados=${StoriesDoDia.ligado(ctx)}\n" +
                "descricaoDosStories=${StoriesDoDia.descricao(ctx)}\n\nMENU DO ROBO:\n$menu\n"
        )
        val produto = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        Canvas(produto).drawColor(Color.rgb(120, 150, 170))
        val jpeg = java.io.ByteArrayOutputStream().also { produto.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        File(pasta, "arte-produto.jpg").writeBytes(Postagens.montarStory(jpeg, "Vestido midi floral: novidade na loja", ""))
        File(pasta, "arte-dica.jpg").writeBytes(StoriesDoDia.arte(ctx, StoriesDoDia.reservaPara(false).first()))
        fotografar(TelaRobo::class.java, "robo")
        fotografar(TelaRespostas::class.java, "respostas")
    }
}
