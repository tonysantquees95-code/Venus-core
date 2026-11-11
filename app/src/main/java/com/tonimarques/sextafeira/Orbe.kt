package com.tonimarques.sextafeira

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * O orbe da Sexta-feira: camadas de "líquido" colorido que se deformam com a voz,
 * brilho, anéis giratórios e partículas. As cores mudam lentamente o tempo todo.
 */
class Orbe(context: Context) : View(context) {

    enum class Estado { OCIOSO, OUVINDO, PENSANDO, FALANDO }

    var estado = Estado.OCIOSO

    /** Modelo do orbe e cor escolhida (0 = automática), vindos da aparência escolhida pela pessoa. */
    var modelo = Tema.Modelo.LIQUIDO
        private set
    private var corFixa = 0

    fun aplicarTema(novo: Tema.Modelo, rgb: Int) {
        modelo = novo
        corFixa = rgb and 0xFFFFFF
        invalidate()
    }

    /** Chamado de tempos em tempos com a cor principal atual, para o resto da tela acompanhar. */
    var aoMudarCor: ((Int) -> Unit)? = null

    private val densidade = resources.displayMetrics.density
    private var nivelVoz = 0f
    private var energia = 0f
    private var tempo = 0f
    private var giro = 0f
    private var ultimoQuadro = 0L
    private var quadros = 0

    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pNucleo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pParticula = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pArco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pBlob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.SCREEN
    }

    private val caminho = Path()
    private val pontos = FloatArray(PONTOS * 2)
    private val retangulo = RectF()
    private val hsv = FloatArray(3)

    // ângulo, distância (x raio), velocidade, tamanho, fase
    private val particulas = List(40) {
        floatArrayOf(
            Random.nextFloat() * 2f * PI.toFloat(),
            1.25f + Random.nextFloat() * 0.75f,
            0.15f + Random.nextFloat() * 0.6f,
            0.8f + Random.nextFloat() * 1.8f,
            Random.nextFloat() * 6.28f,
        )
    }

    init {
        contentDescription = "Orbe da ${Marca.nome}. Toque para falar."
        isClickable = true
    }

    /** Nível do microfone, como vem do reconhecedor de voz (aprox. -2 a 10 dB). */
    fun definirNivelVoz(rmsDb: Float) {
        nivelVoz = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    /** Um "flash" de energia, usado na ativação. */
    fun pulsar() {
        energia = 1f
    }

    private fun cor(matiz: Float, saturacao: Float = 0.78f, valor: Float = 1f): Int {
        hsv[0] = ((matiz % 360f) + 360f) % 360f
        hsv[1] = saturacao
        hsv[2] = valor
        return Color.HSVToColor(hsv)
    }

    private fun clarear(cor: Int, quanto: Float): Int = Color.rgb(
        (Color.red(cor) + (255 - Color.red(cor)) * quanto).toInt(),
        (Color.green(cor) + (255 - Color.green(cor)) * quanto).toInt(),
        (Color.blue(cor) + (255 - Color.blue(cor)) * quanto).toInt(),
    )

    private fun comAlfa(cor: Int, alfa: Float): Int =
        Color.argb((alfa.coerceIn(0f, 1f) * 255).toInt(), Color.red(cor), Color.green(cor), Color.blue(cor))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val agora = System.nanoTime()
        val dt = if (ultimoQuadro == 0L) 0.016f else ((agora - ultimoQuadro) / 1e9f).coerceIn(0f, 0.05f)
        ultimoQuadro = agora
        tempo += dt

        // ----- energia (quanto o orbe "vibra") conforme o estado
        val alvo = when (estado) {
            Estado.OCIOSO -> 0.07f + 0.04f * sin(tempo * 1.1f)
            Estado.OUVINDO -> 0.15f + nivelVoz * 0.85f
            Estado.PENSANDO -> 0.30f + 0.12f * sin(tempo * 5f)
            Estado.FALANDO -> 0.25f + 0.5f * abs(sin(tempo * 7.3f) * sin(tempo * 2.9f + 1f)) + 0.08f * sin(tempo * 17f)
        }
        energia += (alvo - energia) * min(1f, dt * 9f)
        val velocidade = when (estado) {
            Estado.OCIOSO -> 0.22f
            Estado.OUVINDO -> 0.45f + energia
            Estado.PENSANDO -> 1.6f
            Estado.FALANDO -> 0.7f
        }
        giro += dt * velocidade

        // ----- cores: um ciclo completo a cada ~50 s
        val matiz = if (Marca.cicloCompleto) {
            (tempo * 7.2f) % 360f
        } else {
            Marca.MATIZ_VENUS + Marca.OSCILACAO_VENUS * sin(tempo * 0.13f)
        }
        val trio = if (corFixa != 0) Tema.trio(corFixa) else null
        val cor1 = trio?.let { 0xFF000000.toInt() or it[0] } ?: cor(matiz)
        val cor2 = trio?.let { 0xFF000000.toInt() or it[1] } ?: cor(matiz + Marca.deslocamentoCor2)
        val cor3 = trio?.let { 0xFF000000.toInt() or it[2] } ?: cor(matiz + Marca.deslocamentoCor3, 0.7f)

        val cx = width / 2f
        val cy = height / 2f
        val raio = min(width, height) * 0.24f * (1f + energia * 0.16f)

        // ----- brilho de fundo
        pGlow.shader = RadialGradient(
            cx, cy, raio * 2.4f,
            intArrayOf(comAlfa(cor1, 0.30f + 0.25f * energia), comAlfa(cor2, 0.12f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, raio * 2.4f, pGlow)

        // ----- partículas orbitando
        for (p in particulas) {
            val ang = p[0] + giro * p[2]
            val dist = raio * p[1] * (1f + 0.08f * sin(tempo * p[2] * 2f + p[4]))
            val brilho = (0.25f + 0.55f * energia) * (0.5f + 0.5f * sin(tempo * 1.7f + p[4]))
            pParticula.color = comAlfa(if (p[4] > 3.14f) cor2 else cor3, brilho)
            canvas.drawCircle(cx + cos(ang) * dist, cy + sin(ang) * dist, p[3] * densidade, pParticula)
        }

        // ----- anéis estilo painel futurista
        pArco.strokeWidth = 1.4f * densidade
        pArco.color = comAlfa(cor1, 0.35f + 0.25f * energia)
        arco(canvas, cx, cy, raio * 1.42f, giro * 40f, floatArrayOf(10f, 70f, 140f, 25f, 200f, 95f))

        pArco.strokeWidth = 1f * densidade
        pArco.color = comAlfa(cor2, 0.25f + 0.2f * energia)
        val tracos = FloatArray(24)
        for (i in 0 until 12) {
            tracos[i * 2] = i * 30f
            tracos[i * 2 + 1] = 10f
        }
        arco(canvas, cx, cy, raio * 1.62f, -giro * 25f, tracos)

        pArco.color = comAlfa(cor3, 0.12f + 0.1f * energia)
        canvas.drawCircle(cx, cy, raio * 1.28f, pArco)

        when (modelo) {
            Tema.Modelo.LIQUIDO -> desenharLiquido(canvas, cx, cy, raio, cor1, cor2, cor3)
            Tema.Modelo.CRISTAL -> desenharCristal(canvas, cx, cy, raio, cor1, cor2, cor3)
            Tema.Modelo.PULSO -> desenharPulso(canvas, cx, cy, raio, cor1, cor2, cor3)
        }

        // ----- núcleo luminoso
        pNucleo.shader = RadialGradient(
            cx, cy, raio * 0.8f,
            intArrayOf(comAlfa(clarear(cor2, 0.55f), 0.10f + 0.30f * energia), comAlfa(cor2, 0.08f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, raio * 0.8f, pNucleo)

        // ----- reflexo de vidro no alto, à esquerda
        val bx = cx - raio * 0.38f
        val by = cy - raio * 0.42f
        pNucleo.shader = RadialGradient(
            bx, by, raio * 0.42f,
            intArrayOf(comAlfa(Color.WHITE, 0.16f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(bx, by, raio * 0.42f, pNucleo)

        quadros++
        if (quadros % 12 == 0) aoMudarCor?.invoke(cor1)
        postInvalidateOnAnimation()
    }

    /** Três camadas de "líquido" que se deformam. */
    private fun desenharLiquido(canvas: Canvas, cx: Float, cy: Float, raio: Float, cor1: Int, cor2: Int, cor3: Int) {
        val cores = intArrayOf(cor1, cor2, cor3)
        for (camada in 0 until 3) {
            val desloc = camada * 2.1f
            montarForma(cx, cy, raio * (0.92f + camada * 0.05f), desloc)
            val corCamada = cores[camada]
            val fx = cx + cos(giro + desloc) * raio * 0.3f
            val fy = cy + sin(giro + desloc) * raio * 0.3f
            pBlob.shader = RadialGradient(
                fx, fy, raio * 1.35f,
                intArrayOf(comAlfa(clarear(corCamada, 0.3f), 0.5f), comAlfa(corCamada, 0.62f), comAlfa(corCamada, 0.06f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawPath(caminho, pBlob)
        }
    }

    /** Esfera de cristal: esfera lisa com um hexágono facetado girando por dentro. */
    private fun desenharCristal(canvas: Canvas, cx: Float, cy: Float, raio: Float, cor1: Int, cor2: Int, cor3: Int) {
        pBlob.shader = RadialGradient(
            cx - raio * 0.25f, cy - raio * 0.3f, raio * 1.3f,
            intArrayOf(comAlfa(clarear(cor1, 0.55f), 0.75f), comAlfa(cor1, 0.6f), comAlfa(cor3, 0.25f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, raio * (0.95f + 0.04f * energia), pBlob)
        pArco.strokeWidth = 1.2f * densidade
        for (camada in 0 until 2) {
            val r = raio * (0.55f + camada * 0.22f) * (1f + 0.06f * energia)
            val giroCamada = if (camada == 0) giro else -giro * 0.7f
            caminho.reset()
            for (k in 0 until 6) {
                val ang = giroCamada + k * (PI.toFloat() / 3f)
                val x = cx + cos(ang) * r
                val y = cy + sin(ang) * r
                if (k == 0) caminho.moveTo(x, y) else caminho.lineTo(x, y)
            }
            caminho.close()
            pArco.color = comAlfa(if (camada == 0) cor2 else clarear(cor3, 0.3f), 0.45f + 0.3f * energia)
            canvas.drawPath(caminho, pArco)
        }
    }

    /** Ondas concêntricas que saem do centro, mais rápidas e fortes quanto mais energia. */
    private fun desenharPulso(canvas: Canvas, cx: Float, cy: Float, raio: Float, cor1: Int, cor2: Int, cor3: Int) {
        pArco.strokeWidth = 2f * densidade
        for (i in 0 until 4) {
            val fase = ((tempo * (0.35f + energia * 0.9f) + i * 0.25f) % 1f)
            pArco.color = comAlfa(if (i % 2 == 0) cor1 else cor2, (1f - fase) * (0.35f + 0.5f * energia))
            canvas.drawCircle(cx, cy, raio * (0.5f + fase * 1.1f), pArco)
        }
        pBlob.shader = RadialGradient(
            cx, cy, raio * 0.8f,
            intArrayOf(comAlfa(clarear(cor1, 0.5f), 0.9f), comAlfa(cor1, 0.7f), comAlfa(cor3, 0.1f)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, raio * (0.62f + 0.12f * energia), pBlob)
    }

    private fun arco(canvas: Canvas, cx: Float, cy: Float, r: Float, rotacao: Float, inicioEExtensao: FloatArray) {
        retangulo.set(cx - r, cy - r, cx + r, cy + r)
        var i = 0
        while (i + 1 < inicioEExtensao.size) {
            canvas.drawArc(retangulo, inicioEExtensao[i] + rotacao, inicioEExtensao[i + 1], false, pArco)
            i += 2
        }
    }

    /** Monta um contorno fechado e suave, com ondulações que crescem com a energia. */
    private fun montarForma(cx: Float, cy: Float, r: Float, desloc: Float) {
        val amplitude = 0.035f + energia * 0.22f
        for (k in 0 until PONTOS) {
            val t = k.toFloat() / PONTOS * 2f * PI.toFloat()
            val onda = sin(3f * t + tempo * 1.3f + desloc) * 0.5f +
                sin(5f * t - tempo * 1.7f + desloc * 1.3f) * 0.3f +
                sin(2f * t + tempo * 0.7f + desloc * 0.7f) * 0.4f
            val rr = r * (1f + onda * amplitude)
            pontos[k * 2] = cx + cos(t) * rr
            pontos[k * 2 + 1] = cy + sin(t) * rr
        }
        caminho.reset()
        val ultimo = PONTOS - 1
        caminho.moveTo((pontos[ultimo * 2] + pontos[0]) / 2f, (pontos[ultimo * 2 + 1] + pontos[1]) / 2f)
        for (k in 0 until PONTOS) {
            val prox = (k + 1) % PONTOS
            val mx = (pontos[k * 2] + pontos[prox * 2]) / 2f
            val my = (pontos[k * 2 + 1] + pontos[prox * 2 + 1]) / 2f
            caminho.quadTo(pontos[k * 2], pontos[k * 2 + 1], mx, my)
        }
        caminho.close()
    }

    companion object {
        private const val PONTOS = 72
    }
}
