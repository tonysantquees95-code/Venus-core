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
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * O orbe da Sexta-feira: camadas de "líquido" colorido que se deformam com a voz,
 * brilho, anéis giratórios e partículas. As cores mudam lentamente o tempo todo.
 */
class Orbe(context: Context) : View(context) {

    enum class Estado { OCIOSO, OUVINDO, PENSANDO, FALANDO }

    var estado = Estado.OCIOSO

    /** Modelo do orbe e cor escolhida (0 = automática), vindos da aparência escolhida pela pessoa. */
    var modelo = Tema.Modelo.RESPIRAR
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

    private val esfera = Array(N_ESFERA) { i ->
        val y = 1f - (i / (N_ESFERA - 1f)) * 2f
        val raioY = sqrt(1f - y * y)
        val t = i * 2.399963f
        floatArrayOf(cos(t) * raioY, y, sin(t) * raioY, Random.nextFloat())
    }
    private val esferaX = FloatArray(N_ESFERA)
    private val esferaY = FloatArray(N_ESFERA)
    private val esferaZ = FloatArray(N_ESFERA)

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

        desenharEsfera(canvas, cx, cy, raio, cor1, cor2, cor3)

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

    /**
     * A esfera de partículas com constelações (a mesma do site): pontos numa esfera girando, ligados quando ficam perto.
     * Os cinco "movimentos" só mudam o jeito de se mexer; as cores são as escolhidas na aparência.
     */
    private fun desenharEsfera(canvas: Canvas, cx: Float, cy: Float, raio: Float, cor1: Int, cor2: Int, cor3: Int) {
        val r0 = raio * 1.3f
        var rotY = giro
        var rotX = 0.25f
        var escala = 1f
        var bob = 0f
        var ondaAmp = 0f
        when (modelo) {
            Tema.Modelo.RESPIRAR -> { rotY = giro * 0.6f; escala = 1f + 0.06f * sin(tempo * 1.3f) }
            Tema.Modelo.GIRO -> { rotY = giro * 3.2f; rotX = 0.25f + 0.15f * sin(giro * 0.5f) }
            Tema.Modelo.BATIDA -> {
                rotY = giro * 0.5f
                val ciclo = (tempo * 1.25f) % 1f
                val segundo = ((ciclo - 0.22f) + 1f) % 1f
                escala = 1f + 0.13f * (exp(-ciclo * 9f) + 0.7f * exp(-segundo * 9f))
            }
            Tema.Modelo.ONDA -> { rotY = giro * 0.8f; ondaAmp = 0.085f }
            Tema.Modelo.FLUTUAR -> {
                rotY = giro * 0.5f + 0.5f * sin(tempo * 0.6f)
                rotX = 0.25f + 0.35f * sin(tempo * 0.8f)
                bob = r0 * 0.07f * sin(tempo * 1.1f)
            }
        }
        escala *= 1f + 0.10f * energia
        val ca = cos(rotY)
        val sa = sin(rotY)
        val cb = cos(rotX)
        val sb = sin(rotX)
        val r = r0 * escala
        for (i in 0 until N_ESFERA) {
            val q = esfera[i]
            val x1 = q[0] * ca + q[2] * sa
            val z1 = -q[0] * sa + q[2] * ca
            val y2 = q[1] * cb - z1 * sb
            val z2 = q[1] * sb + z1 * cb
            val pulso = 1f + 0.03f * sin(tempo * 1.1f + q[3] * 9f) + ondaAmp * sin(tempo * 3.2f + q[1] * 5.5f)
            esferaX[i] = cx + x1 * r * pulso
            esferaY[i] = cy + bob + y2 * r * pulso
            esferaZ[i] = z2
        }
        pArco.strokeWidth = 0.8f * densidade
        val lim = (r * 0.2f) * (r * 0.2f)
        for (i in 0 until N_ESFERA) for (j in i + 1 until N_ESFERA) {
            val dx = esferaX[i] - esferaX[j]
            val dy = esferaY[i] - esferaY[j]
            val d2 = dx * dx + dy * dy
            if (d2 < lim && esferaZ[i] + esferaZ[j] > -0.4f) {
                val a = (1f - d2 / lim) * (0.35f + 0.3f * energia) * ((esferaZ[i] + esferaZ[j] + 2f) / 4f)
                pArco.color = comAlfa(clarear(cor3, 0.45f), a)
                canvas.drawLine(esferaX[i], esferaY[i], esferaX[j], esferaY[j], pArco)
            }
        }
        // fundo primeiro, frente depois
        for (passo in 0..1) for (i in 0 until N_ESFERA) {
            val z = esferaZ[i]
            if ((z >= 0f) != (passo == 1)) continue
            val f = (z + 1f) / 2f
            pParticula.color = comAlfa(if (esfera[i][3] > 0.5f) cor1 else cor2, 0.25f + f * 0.75f)
            canvas.drawCircle(esferaX[i], esferaY[i], (0.9f + f * 2.1f) * densidade, pParticula)
        }
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
        private const val N_ESFERA = 130
    }
}
