package app.vozlimpia

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private var entrada: Uri? = null
    private var salida: Uri? = null
    private var salidaEsVideo = false

    private lateinit var lblArchivo: TextView
    private lateinit var btnElegir: Button
    private lateinit var chkReparar: CheckBox
    private lateinit var chkRealzar: CheckBox
    private lateinit var btnLimpiar: Button
    private lateinit var barra: ProgressBar
    private lateinit var lblEstado: TextView
    private lateinit var lblInforme: TextView
    private lateinit var filaResultado: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun espacio(h: Int) = View(this).apply { minimumHeight = dp(h) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        raiz.addView(TextView(this).apply {
            text = "🎤 VozLimpia"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        raiz.addView(TextView(this).apply {
            text = "Repara la saturación y rescata la voz en tus videos de conciertos. " +
                    "Todo se procesa en tu teléfono."
            textSize = 15f
        })
        raiz.addView(espacio(16))

        btnElegir = Button(this).apply {
            text = "Elegir video o audio"
            setOnClickListener { elegirArchivo() }
        }
        raiz.addView(btnElegir)
        lblArchivo = TextView(this).apply { text = "Ningún archivo elegido"; textSize = 14f }
        raiz.addView(lblArchivo)
        raiz.addView(espacio(12))

        chkReparar = CheckBox(this).apply { text = "Reparar picos saturados"; isChecked = true }
        chkRealzar = CheckBox(this).apply { text = "Realzar la voz (EQ + compresión)"; isChecked = true }
        raiz.addView(chkReparar)
        raiz.addView(chkRealzar)
        raiz.addView(espacio(12))

        btnLimpiar = Button(this).apply {
            text = "Limpiar audio"
            isEnabled = false
            setOnClickListener { procesar() }
        }
        raiz.addView(btnLimpiar)

        barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
        }
        raiz.addView(barra)
        lblEstado = TextView(this).apply { textSize = 14f }
        raiz.addView(lblEstado)
        raiz.addView(espacio(8))

        lblInforme = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        raiz.addView(lblInforme)
        raiz.addView(espacio(8))

        filaResultado = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val peso = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        filaResultado.addView(Button(this).apply {
            text = "Abrir resultado"
            setOnClickListener { abrirResultado() }
        }, peso)
        filaResultado.addView(Button(this).apply {
            text = "Compartir"
            setOnClickListener { compartirResultado() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        raiz.addView(filaResultado)

        setContentView(ScrollView(this).apply { addView(raiz) })
        manejarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarIntent(intent)
    }

    /** Permite "Compartir → VozLimpia" desde la galería. */
    private fun manejarIntent(i: Intent?) {
        if (i != null && i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (u != null) elegir(u)
        }
    }

    private fun elegirArchivo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { elegir(it) }
    }

    private fun elegir(u: Uri) {
        entrada = u
        lblArchivo.text = Procesador.nombreArchivo(this, u)
        btnLimpiar.isEnabled = true
        filaResultado.visibility = View.GONE
        lblInforme.text = ""
        lblEstado.text = ""
    }

    private fun ocupado(si: Boolean) {
        btnElegir.isEnabled = !si
        btnLimpiar.isEnabled = !si && entrada != null
        barra.visibility = if (si) View.VISIBLE else View.INVISIBLE
        if (si) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun procesar() {
        val uri = entrada ?: return
        val reparar = chkReparar.isChecked
        val realzar = chkRealzar.isChecked
        ocupado(true)
        filaResultado.visibility = View.GONE
        lblInforme.text = ""
        barra.progress = 0
        Thread {
            try {
                val r = Procesador.procesar(this, uri, reparar, realzar) { p, msg ->
                    runOnUiThread {
                        barra.progress = (p * 100).toInt()
                        lblEstado.text = msg
                    }
                }
                runOnUiThread {
                    salida = r.uri
                    salidaEsVideo = r.esVideo
                    lblEstado.text = "Listo ✅"
                    lblInforme.text = r.informe
                    filaResultado.visibility = View.VISIBLE
                    ocupado(false)
                }
            } catch (e: Throwable) {
                val msg = if (e is OutOfMemoryError)
                    "Memoria insuficiente. Prueba con un video más corto (menos de unos 10 minutos)."
                else e.message ?: e.toString()
                runOnUiThread {
                    lblEstado.text = "Error"
                    lblInforme.text = msg
                    ocupado(false)
                }
            }
        }.start()
    }

    private fun mime() = if (salidaEsVideo) "video/mp4" else "audio/mp4"

    private fun abrirResultado() {
        val u = salida ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, mime())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            lblEstado.text = "No encontré una app para abrirlo. Búscalo en tu galería (carpeta VozLimpia)."
        }
    }

    private fun compartirResultado() {
        val u = salida ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime()
            putExtra(Intent.EXTRA_STREAM, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Compartir"))
    }
}
