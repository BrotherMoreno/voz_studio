package app.vozlimpia

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

object Procesador {

    class Resultado(val uri: Uri, val esVideo: Boolean, val informe: String)

    fun nombreArchivo(ctx: Context, u: Uri): String {
        var res: String? = null
        try {
            ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) res = c.getString(i)
                }
            }
        } catch (e: Exception) {
        }
        return res ?: "audio"
    }

    fun procesar(
        ctx: Context, src: Uri, reparar: Boolean, realzar: Boolean,
        cb: (Float, String) -> Unit
    ): Resultado {
        val t0 = System.currentTimeMillis()

        cb(0.03f, "Leyendo el audio…")
        val audio = Decodificador.decodificar(ctx, src) { p -> cb(0.03f + 0.32f * p, "Leyendo el audio…") }
        val ch = audio.canales
        val n = audio.n

        val antes = Dsp.diagnosticar(ch, n)
        var reparados = 0
        if (reparar) {
            cb(0.36f, "Reparando los picos saturados…")
            reparados = Dsp.declip(ch, n)
        }
        Dsp.normalizarPico(ch, n, 0.9f)
        if (realzar) {
            cb(0.45f, "Ecualizando para destacar la voz…")
            Dsp.realzarVoz(ch, n, audio.sr)
        }
        cb(0.58f, "Ajustando el volumen…")
        Dsp.normalizarVolumen(ch, n)
        val despues = Dsp.diagnosticar(ch, n)

        val tmp = File(ctx.cacheDir, "vozlimpia_tmp.mp4")
        var esVideo = Codificador.tieneVideo(ctx, src)
        cb(0.62f, "Creando el archivo final…")
        val prog: (Float) -> Unit = { p -> cb(0.62f + 0.33f * p, "Creando el archivo final…") }
        var aviso = ""
        try {
            Codificador.escribir(ctx, src, audio, tmp, esVideo, prog)
        } catch (e: Exception) {
            if (!esVideo) throw e
            // El video no se pudo copiar (formato raro): guardamos solo el audio mejorado.
            esVideo = false
            aviso = "\nNo pude copiar el video de este formato; guardé solo el audio mejorado."
            Codificador.escribir(ctx, src, audio, tmp, false, prog)
        }

        cb(0.96f, "Guardando…")
        val stem = nombreArchivo(ctx, src).substringBeforeLast('.')
        val nombre = stem + "_limpio" + if (esVideo) ".mp4" else ".m4a"
        val uri = guardar(ctx, tmp, nombre, esVideo)
        tmp.delete()
        cb(1f, "Listo")

        val seg = (System.currentTimeMillis() - t0) / 1000
        val carpeta = if (esVideo) "Movies/VozLimpia" else "Music/VozLimpia"
        val informe = "ANTES : ${antes.texto()}\nDESPUÉS: ${despues.texto()}\n" +
                "Picos reparados: $reparados$aviso\n\nTiempo: $seg s\nGuardado en: $carpeta/$nombre"
        return Resultado(uri, esVideo, informe)
    }

    private fun guardar(ctx: Context, archivo: File, nombre: String, esVideo: Boolean): Uri {
        val cr = ctx.contentResolver
        val vals = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
            put(MediaStore.MediaColumns.MIME_TYPE, if (esVideo) "video/mp4" else "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (esVideo) "Movies/VozLimpia" else "Music/VozLimpia")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coleccion = if (esVideo)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = cr.insert(coleccion, vals) ?: throw IOException("No pude crear el archivo de salida.")
        val salida = cr.openOutputStream(uri) ?: throw IOException("No pude escribir el archivo de salida.")
        salida.use { o -> archivo.inputStream().use { it.copyTo(o) } }
        vals.clear()
        vals.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, vals, null, null)
        return uri
    }
}
