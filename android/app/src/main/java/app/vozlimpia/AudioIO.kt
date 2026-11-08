package app.vozlimpia

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Audio en memoria: un arreglo de floats por canal (1 o 2 canales). */
class Audio(val sr: Int, val canales: Array<FloatArray>, val n: Int)

object Decodificador {

    fun decodificar(ctx: Context, uri: Uri, progreso: (Float) -> Unit): Audio {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var pista = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    pista = i
                    break
                }
            }
            if (pista < 0) throw IllegalStateException("El archivo no tiene pista de audio.")
            ex.selectTrack(pista)
            val fmt = ex.getTrackFormat(pista)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var chIn = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcm16 = true

            val d = MediaCodec.createDecoderByType(mime)
            dec = d
            d.configure(fmt, null, null, 0)
            d.start()

            val capInicial = (((durUs / 1_000_000.0) + 2.0) * sr).toInt().coerceAtLeast(sr)
            var out = Array(2) { FloatArray(capInicial) }
            var n = 0

            val info = MediaCodec.BufferInfo()
            var entradaLista = false
            var salidaLista = false
            while (!salidaLista) {
                if (!entradaLista) {
                    val ii = d.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val b = d.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(b, 0)
                        if (sz < 0) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            entradaLista = true
                        } else {
                            d.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = d.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = d.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    chIn = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcm16 = !(f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT)
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = d.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        val bytesPorMuestra = if (pcm16) 2 else 4
                        val frames = info.size / bytesPorMuestra / chIn
                        if (n + frames > out[0].size) {
                            val nuevo = max(out[0].size * 3 / 2, n + frames)
                            val viejo = out
                            out = Array(2) { viejo[it].copyOf(nuevo) }
                        }
                        val o = out
                        val base = n
                        if (pcm16) {
                            val sb = ob.asShortBuffer()
                            for (f in 0 until frames) for (c in 0 until chIn) {
                                val v = sb.get() / 32768f
                                if (c < 2) o[c][base + f] = v
                            }
                        } else {
                            val fb = ob.asFloatBuffer()
                            for (f in 0 until frames) for (c in 0 until chIn) {
                                val v = fb.get()
                                if (c < 2) o[c][base + f] = v
                            }
                        }
                        n += frames
                    }
                    d.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) salidaLista = true
                    if (durUs > 0) progreso((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                }
            }
            val canales = min(chIn, 2)
            val res = Array(canales) { out[it] }
            if (n == 0) throw IllegalStateException("No pude leer audio de este archivo.")
            return Audio(sr, res, n)
        } finally {
            try { dec?.stop() } catch (e: Exception) { }
            try { dec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }
}

object Codificador {

    fun tieneVideo(ctx: Context, uri: Uri): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, uri, null)
            (0 until ex.trackCount).any {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
        } catch (e: Exception) {
            false
        } finally {
            ex.release()
        }
    }

    private fun rotacion(ctx: Context, uri: Uri): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            r.release()
        }
    }

    /**
     * Codifica el audio mejorado a AAC y lo guarda en [destino] (.mp4/.m4a).
     * Si [conVideo], copia la pista de video del original sin recodificarla.
     */
    fun escribir(
        ctx: Context, src: Uri, audio: Audio, destino: File,
        conVideo: Boolean, progreso: (Float) -> Unit
    ) {
        destino.delete()
        val ch = audio.canales.size
        val sr = audio.sr
        val total = audio.n

        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vex: MediaExtractor? = if (conVideo) MediaExtractor() else null
        var iniciado = false
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()

            var vTrack = -1
            val vBuf = ByteBuffer.allocate(if (conVideo) 8 shl 20 else 1)
            val vInfo = MediaCodec.BufferInfo()
            var vFmt: MediaFormat? = null
            if (vex != null) {
                vex.setDataSource(ctx, src, null)
                var vi = -1
                for (i in 0 until vex.trackCount) {
                    if (vex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        vi = i
                        break
                    }
                }
                if (vi < 0) throw IllegalStateException("Sin pista de video")
                vex.selectTrack(vi)
                vFmt = vex.getTrackFormat(vi)
                muxer.setOrientationHint(rotacion(ctx, src))
            }

            // Copia al archivo final las muestras de video hasta el instante indicado.
            fun bombearVideo(hastaUs: Long) {
                val e = vex ?: return
                while (true) {
                    val t = e.sampleTime
                    if (t < 0 || t > hastaUs) return
                    vBuf.clear()
                    val sz = e.readSampleData(vBuf, 0)
                    if (sz < 0) return
                    val flags = if (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    vInfo.set(0, sz, t, flags)
                    muxer.writeSampleData(vTrack, vBuf, vInfo)
                    e.advance()
                }
            }

            var aTrack = -1
            var enviados = 0
            var entradaLista = false
            var salidaLista = false
            val info = MediaCodec.BufferInfo()
            while (!salidaLista) {
                if (!entradaLista) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = enc.getInputBuffer(idx)!!
                        ib.clear()
                        ib.order(ByteOrder.nativeOrder())
                        val ptsUs = enviados.toLong() * 1_000_000L / sr
                        val nF = min(ib.capacity() / (2 * ch), total - enviados)
                        if (nF <= 0) {
                            enc.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            entradaLista = true
                        } else {
                            val sb = ib.asShortBuffer()
                            for (f in 0 until nF) for (c in 0 until ch) {
                                val v = (audio.canales[c][enviados + f].coerceIn(-1f, 1f) * 32767f).toInt()
                                sb.put(v.toShort())
                            }
                            enc.queueInputBuffer(idx, 0, nF * 2 * ch, ptsUs, 0)
                            enviados += nF
                            progreso(enviados.toFloat() / total)
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    aTrack = muxer.addTrack(enc.outputFormat)
                    val vf = vFmt
                    if (vf != null) vTrack = muxer.addTrack(vf)
                    muxer.start()
                    iniciado = true
                } else if (oi >= 0) {
                    val ob = enc.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && iniciado) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        bombearVideo(info.presentationTimeUs)
                        muxer.writeSampleData(aTrack, ob, info)
                    }
                    enc.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) salidaLista = true
                }
            }
            bombearVideo(Long.MAX_VALUE)
            if (iniciado) {
                muxer.stop()
                iniciado = false
            }
        } finally {
            try { enc.stop() } catch (e: Exception) { }
            try { enc.release() } catch (e: Exception) { }
            try { muxer.release() } catch (e: Exception) { }
            vex?.release()
        }
    }
}
