package app.vozlimpia

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Filtro biquad (fórmulas RBJ), procesa en el sitio. */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: FloatArray, n: Int) {
        var s1 = z1
        var s2 = z2
        for (i in 0 until n) {
            val inp = x[i].toDouble()
            val out = b0 * inp + s1
            s1 = b1 * inp - a1 * out + s2
            s2 = b2 * inp - a2 * out
            x[i] = out.toFloat()
        }
        z1 = s1
        z2 = s2
    }

    companion object {
        private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun highpass(sr: Int, f: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * f / sr
            val cw = cos(w)
            val al = sin(w) / (2 * q)
            return norm((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al)
        }

        fun peak(sr: Int, f: Double, gainDb: Double, q: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / sr
            val cw = cos(w)
            val al = sin(w) / (2 * q)
            return norm(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a)
        }

        fun lowShelf(sr: Int, f: Double, gainDb: Double, q: Double = 0.7071): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / sr
            val cw = cos(w)
            val t = 2 * sqrt(a) * (sin(w) / (2 * q))
            return norm(
                a * ((a + 1) - (a - 1) * cw + t),
                2 * a * ((a - 1) - (a + 1) * cw),
                a * ((a + 1) - (a - 1) * cw - t),
                (a + 1) + (a - 1) * cw + t,
                -2 * ((a - 1) + (a + 1) * cw),
                (a + 1) + (a - 1) * cw - t
            )
        }

        fun highShelf(sr: Int, f: Double, gainDb: Double, q: Double = 0.7071): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / sr
            val cw = cos(w)
            val t = 2 * sqrt(a) * (sin(w) / (2 * q))
            return norm(
                a * ((a + 1) + (a - 1) * cw + t),
                -2 * a * ((a - 1) + (a + 1) * cw),
                a * ((a + 1) + (a - 1) * cw - t),
                (a + 1) - (a - 1) * cw + t,
                2 * ((a - 1) - (a + 1) * cw),
                (a + 1) - (a - 1) * cw - t
            )
        }
    }
}

class Diag(val picoDb: Double, val saturadoPct: Double) {
    fun texto() = "pico %+.1f dBFS · muestras saturadas %.2f %%".format(picoDb, saturadoPct)
}

object Dsp {

    fun diagnosticar(ch: Array<FloatArray>, n: Int): Diag {
        var pico = 0f
        var sat = 0L
        for (c in ch) for (i in 0 until n) {
            val a = abs(c[i])
            if (a > pico) pico = a
            if (a >= 0.99f) sat++
        }
        val total = (n.toDouble() * ch.size).coerceAtLeast(1.0)
        return Diag(20 * log10(max(pico.toDouble(), 1e-9)), sat / total * 100)
    }

    // ------------------------------------------------------------ declipping (Hermite)
    /** Reconstruye los picos recortados. Devuelve cuántos picos reparó. */
    fun declip(ch: Array<FloatArray>, n: Int): Int {
        var pico = 0f
        for (c in ch) for (i in 0 until n) {
            val a = abs(c[i])
            if (a > pico) pico = a
        }
        if (pico < 0.3f) return 0
        val thr = 0.9f * pico
        var altos = 0L
        for (c in ch) for (i in 0 until n) if (abs(c[i]) >= thr) altos++
        if (altos.toDouble() / (n.toDouble() * ch.size) < 3e-4) return 0
        var total = 0
        for (c in ch) total += declipCanal(c, n, thr, 96)
        return total
    }

    private fun declipCanal(c: FloatArray, n: Int, thr: Float, maxRun: Int): Int {
        var arreglados = 0
        val tope = 3f * thr
        var i = 0
        while (i < n) {
            if (abs(c[i]) < thr) {
                i++
                continue
            }
            val s = i
            var suma = 0.0
            while (i < n && abs(c[i]) >= thr) {
                suma += c[i]
                i++
            }
            val e = i - 1
            if (e - s + 1 > maxRun || s < 2 || e > n - 3) continue
            val i0 = s - 1
            val i1 = e + 1
            val len = i1 - i0
            val m0 = (c[i0] - c[i0 - 1]) * len
            val m1 = (c[i1 + 1] - c[i1]) * len
            val sg = if (suma > 0) 1f else -1f
            for (k in 1 until len) {
                val t = k.toFloat() / len
                val t2 = t * t
                val t3 = t2 * t
                val v = (2f * t3 - 3f * t2 + 1f) * c[i0] + (t3 - 2f * t2 + t) * m0 +
                        (-2f * t3 + 3f * t2) * c[i1] + (t3 - t2) * m1
                c[s + k - 1] = sg * (sg * v).coerceIn(thr, tope)
            }
            arreglados++
        }
        return arreglados
    }

    // ------------------------------------------------------------ niveles
    fun normalizarPico(ch: Array<FloatArray>, n: Int, objetivo: Float) {
        var pico = 0f
        for (c in ch) for (i in 0 until n) {
            val a = abs(c[i])
            if (a > pico) pico = a
        }
        if (pico < 1e-6f) return
        val g = objetivo / pico
        for (c in ch) for (i in 0 until n) c[i] *= g
    }

    /** Limitador suave: no deja pasar nada por encima de [techo] y apenas toca lo que está bajo [rodilla]. */
    fun limitadorSuave(ch: Array<FloatArray>, n: Int, techo: Float, rodilla: Float) {
        val rango = techo - rodilla
        for (c in ch) for (i in 0 until n) {
            val v = c[i]
            val a = abs(v)
            if (a > rodilla) {
                val y = rodilla + rango * tanh((a - rodilla) / rango)
                c[i] = if (v < 0f) -y else y
            }
        }
    }

    private fun comprimir(
        ch: Array<FloatArray>, n: Int, sr: Int,
        umbralDb: Double, ratio: Double, ataqueMs: Double, liberacionMs: Double
    ) {
        val aA = exp(-1.0 / (ataqueMs * 0.001 * sr))
        val aR = exp(-1.0 / (liberacionMs * 0.001 * sr))
        val pendiente = 1.0 - 1.0 / ratio
        var envDb = -120.0
        for (i in 0 until n) {
            var m = 0f
            for (c in ch) {
                val v = abs(c[i])
                if (v > m) m = v
            }
            val nivel = 20.0 * log10(max(m.toDouble(), 1e-6))
            val coef = if (nivel > envDb) aA else aR
            envDb = coef * envDb + (1 - coef) * nivel
            val exceso = envDb - umbralDb
            if (exceso > 0) {
                val g = 10.0.pow(-(exceso * pendiente) / 20.0).toFloat()
                for (c in ch) c[i] *= g
            }
        }
    }

    /** Equivalente a la cadena "mezcla" de la versión de PC (sin IA): quita graves y destaca la voz. */
    fun realzarVoz(ch: Array<FloatArray>, n: Int, sr: Int) {
        for (c in ch) {
            val cadena = listOf(
                Biquad.highpass(sr, 100.0),
                Biquad.lowShelf(sr, 250.0, -9.0),
                Biquad.peak(sr, 400.0, -2.5, 0.9),
                Biquad.peak(sr, 2800.0, 4.0, 0.9),
                Biquad.highShelf(sr, 6000.0, 2.5)
            )
            for (f in cadena) f.process(c, n)
        }
        comprimir(ch, n, sr, -24.0, 3.5, 4.0, 90.0)
    }

    /** Sube el volumen a un nivel parejo y protege los picos (techo de -1 dBFS). */
    fun normalizarVolumen(ch: Array<FloatArray>, n: Int, objetivoRmsDb: Double = -17.0) {
        var suma = 0.0
        for (c in ch) for (i in 0 until n) suma += c[i].toDouble() * c[i]
        val rms = sqrt(suma / (n.toDouble() * ch.size).coerceAtLeast(1.0))
        val db = 20 * log10(max(rms, 1e-9))
        val gDb = (objetivoRmsDb - db).coerceIn(-12.0, 12.0)
        val g = 10.0.pow(gDb / 20).toFloat()
        for (c in ch) for (i in 0 until n) c[i] *= g
        limitadorSuave(ch, n, 0.89f, 0.6f)
    }
}
