package com.tonimarques.sextafeira

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max

/**
 * Gesto para chamar a Vênus: torcer o pulso para um lado e para o outro, duas vezes
 * (o mesmo movimento do atalho da câmera do Motorola). Usa o giroscópio:
 * procura giros fortes em volta do eixo comprido do celular, alternando o sentido.
 */
class GestoTorcer(contexto: Context, private val aoGesto: () -> Unit) : SensorEventListener {

    private val sensores = contexto.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val giroscopio: Sensor? = sensores.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val detector = Detector()

    val disponivel get() = giroscopio != null

    fun iniciar(): Boolean {
        val g = giroscopio ?: return false
        return sensores.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME)
    }

    fun parar() {
        sensores.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (detector.amostra(e.values[0], e.values[1], e.values[2], SystemClock.elapsedRealtime())) aoGesto()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** A lógica do gesto, separada do sensor para poder ser testada. Valores em rad/s. */
    class Detector(
        private val limiar: Float = 4.5f,
        private val janelaMs: Long = 1500,
        private val picosNecessarios: Int = 4,
    ) {
        private val picos = ArrayDeque<Pair<Long, Int>>()
        private var ultimoSinal = 0
        private var esperaAte = 0L

        fun amostra(x: Float, y: Float, z: Float, agora: Long): Boolean {
            if (agora < esperaAte) return false
            while (picos.isNotEmpty() && agora - picos.first().first > janelaMs) picos.removeFirst()
            if (picos.isEmpty()) ultimoSinal = 0
            // giro forte e principalmente em volta do eixo comprido (y)
            if (abs(y) < limiar || abs(y) < 1.5f * max(abs(x), abs(z))) return false
            val sinal = if (y > 0) 1 else -1
            if (sinal == ultimoSinal) return false
            ultimoSinal = sinal
            picos.addLast(agora to sinal)
            if (picos.size >= picosNecessarios) {
                picos.clear()
                ultimoSinal = 0
                esperaAte = agora + 2000
                return true
            }
            return false
        }
    }
}
