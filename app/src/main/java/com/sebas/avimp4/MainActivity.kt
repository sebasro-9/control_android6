package com.sebas.avimp4

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var estado: TextView
    private lateinit var barra: ProgressBar
    private lateinit var boton: Button
    private lateinit var saltarBtn: Button

    private val cola = ArrayDeque<Uri>()
    private var total = 0
    private var hechos = 0
    private var fallidos = 0
    private var ocupado = false
    private val registro = StringBuilder()
    private var nombreActual = ""

    private var transformer: Transformer? = null
    private var alFallar: ((String) -> Unit)? = null
    private var tmpActual: File? = null
    private var ultimoAvance = 0L
    private var ultimoProgreso = -1
    private var ultimoTam = -1L
    @Volatile private var saltado = false
    @Volatile private var proceso: Process? = null
    @Volatile private var destruida = false
    private val holder = ProgressHolder()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val elegir =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) encolar(uris)
        }

    private val progreso = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            var avanzo = false
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE &&
                holder.progress != ultimoProgreso
            ) {
                ultimoProgreso = holder.progress
                barra.progress = holder.progress
                avanzo = true
            }
            val tam = tmpActual?.length() ?: 0L
            if (tam != ultimoTam) {
                ultimoTam = tam
                avanzo = true
            }
            val ahora = SystemClock.elapsedRealtime()
            if (avanzo) {
                ultimoAvance = ahora
            } else if (ahora - ultimoAvance > 30_000) {
                abortarMedia3("se trabó (30 s sin avanzar)")
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estado = findViewById(R.id.estado)
        barra = findViewById(R.id.barra)
        boton = findViewById(R.id.boton)
        saltarBtn = findViewById(R.id.saltar)
        boton.setOnClickListener { elegir.launch(arrayOf("video/*")) }
        saltarBtn.setOnClickListener { saltar() }
        if (savedInstanceState == null) manejarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarIntent(intent)
    }

    private fun manejarIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.toList() ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) encolar(uris)
    }

    private fun encolar(uris: List<Uri>) {
        if (!ocupado) {
            total = 0; hechos = 0; fallidos = 0
            registro.clear()
        }
        cola.addAll(uris)
        total += uris.size
        if (!ocupado) siguiente()
    }

    private fun mostrar(paso: String) {
        estado.text = "Convirtiendo ${hechos + fallidos + 1} de $total\n$nombreActual\n$paso\n\n$registro"
    }

    private fun siguiente() {
        if (destruida) return
        val uri = cola.removeFirstOrNull() ?: return terminar()
        ocupado = true
        saltado = false
        saltarBtn.visibility = android.view.View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val nombre = nombreDe(uri)
        val salida = nombre.substringBeforeLast('.', nombre) + ".mp4"
        nombreActual = nombre
        mostrar("Convirtiendo con el hardware del teléfono…")
        barra.progress = 0
        media3(
            MediaItem.fromUri(uri),
            ok = { tmp -> guardar(tmp, salida, nombre) },
            error = { e -> rutaFFmpeg(uri, nombre, salida, e) }
        )
    }

    private fun media3(item: MediaItem, ok: (File) -> Unit, error: (String) -> Unit) {
        val tmp = File(cacheDir, "m3_${System.nanoTime()}.mp4")
        val ref = java.util.concurrent.atomic.AtomicReference<Transformer>()
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (transformer !== ref.get()) return
                    limpiarMedia3()
                    ok(tmp)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (transformer !== ref.get()) return
                    limpiarMedia3()
                    tmp.delete()
                    error(exportException.errorCodeName)
                }
            })
            .build()
        ref.set(t)
        transformer = t
        tmpActual = tmp
        alFallar = error
        ultimoProgreso = -1
        ultimoTam = -1L
        ultimoAvance = SystemClock.elapsedRealtime()
        try {
            t.start(item, tmp.absolutePath)
            handler.removeCallbacks(progreso)
            handler.post(progreso)
        } catch (e: Exception) {
            limpiarMedia3()
            tmp.delete()
            error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun limpiarMedia3() {
        handler.removeCallbacks(progreso)
        transformer = null
        alFallar = null
        tmpActual = null
    }

    private fun abortarMedia3(motivo: String) {
        val t = transformer ?: return
        val tmp = tmpActual
        val cb = alFallar
        limpiarMedia3()
        runCatching { t.cancel() }
        tmp?.delete()
        cb?.invoke(motivo)
    }

    private fun saltar() {
        if (!ocupado) return
        val t = transformer
        if (t != null) {
            val tmp = tmpActual
            limpiarMedia3()
            runCatching { t.cancel() }
            tmp?.delete()
            fallo(nombreActual, "Saltado")
            return
        }
        saltado = true
        proceso?.destroy()
    }

    private fun rutaFFmpeg(uri: Uri, nombre: String, salida: String, errorMedia3: String) {
        mostrar("Formato antiguo: paso 1/2 con FFmpeg…")
        barra.progress = 0
        io.execute {
            val entrada = File(cacheDir, "in_${System.nanoTime()}")
            val intermedio = File(cacheDir, "ff_${System.nanoTime()}.mp4")
            val resultado = runCatching {
                contentResolver.openInputStream(uri).use { i ->
                    requireNotNull(i) { "No se pudo leer el archivo" }
                    entrada.outputStream().use { i.copyTo(it, 1 shl 20) }
                }
                ejecutarFFmpeg(entrada, intermedio)
            }
            entrada.delete()
            runOnUiThread {
                if (destruida) {
                    intermedio.delete()
                    return@runOnUiThread
                }
                val err = resultado.exceptionOrNull()
                if (saltado) {
                    intermedio.delete()
                    fallo(nombre, "Saltado")
                    return@runOnUiThread
                }
                if (err != null) {
                    intermedio.delete()
                    fallo(nombre, "$errorMedia3\n   FFmpeg: ${err.message}")
                    return@runOnUiThread
                }
                mostrar("Paso 2/2: pasando a H.264…")
                barra.progress = 0
                media3(
                    MediaItem.fromUri(Uri.fromFile(intermedio)),
                    ok = { tmp ->
                        intermedio.delete()
                        guardar(tmp, salida, nombre)
                    },
                    error = { guardar(intermedio, salida, nombre) }
                )
            }
        }
    }

    private fun ejecutarFFmpeg(entrada: File, salida: File) {
        val bin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
        check(bin.exists()) { "FFmpeg no disponible para este procesador" }
        val cmd = listOf(
            bin.absolutePath, "-hide_banner", "-nostdin", "-y",
            "-i", entrada.absolutePath,
            "-map", "0:v:0", "-map", "0:a:0?",
            "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ac", "2",
            "-progress", "pipe:1", "-nostats",
            salida.absolutePath
        )
        check(!saltado) { "Saltado" }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        proceso = p
        val ultimaLinea = AtomicLong(SystemClock.elapsedRealtime())
        val trabado = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            while (p.isAlive) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                if (SystemClock.elapsedRealtime() - ultimaLinea.get() > 90_000) {
                    trabado.set(true)
                    p.destroy()
                }
            }
        }.apply { isDaemon = true }.start()
        val ultimas = ArrayDeque<String>()
        val claveValor = Regex("^[a-z_0-9]+=.*")
        val duracion = Regex("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")
        var durUs = 0L
        p.inputStream.bufferedReader().forEachLine { l ->
            ultimaLinea.set(SystemClock.elapsedRealtime())
            if (l.startsWith("out_time_us=")) {
                val t = l.substringAfter('=').toLongOrNull() ?: 0L
                if (durUs > 0) {
                    val pct = (t * 100 / durUs).toInt().coerceIn(0, 100)
                    runOnUiThread { barra.progress = pct }
                }
            } else if (!claveValor.matches(l)) {
                if (durUs == 0L) {
                    duracion.find(l)?.let { m ->
                        val (h, mi, s) = m.destructured
                        durUs = ((h.toLong() * 3600 + mi.toLong() * 60) * 1_000_000L +
                                (s.toDouble() * 1_000_000).toLong())
                    }
                }
                if (l.isNotBlank()) {
                    ultimas.addLast(l.trim())
                    if (ultimas.size > 4) ultimas.removeFirst()
                }
            }
        }
        val codigo = p.waitFor()
        proceso = null
        if (trabado.get()) throw RuntimeException("se trabó (90 s sin avanzar)")
        if (codigo != 0 || !salida.exists() || salida.length() == 0L) {
            throw RuntimeException(ultimas.joinToString("\n   ").ifBlank { "código $codigo" })
        }
    }

    private fun fallo(nombre: String, msg: String) {
        fallidos++
        registro.append("✗ $nombre\n   $msg\n")
        siguiente()
    }

    private fun guardar(tmp: File, salida: String, original: String) {
        barra.progress = 100
        mostrar("Guardando $salida…")
        io.execute {
            val error = runCatching { copiarAGaleria(tmp, salida) }.exceptionOrNull()
            tmp.delete()
            runOnUiThread {
                if (error == null) {
                    hechos++
                    registro.append("✓ $salida\n")
                    siguiente()
                } else {
                    fallo(original, "No se pudo guardar: ${error.message}")
                }
            }
        }
    }

    private fun copiarAGaleria(archivo: File, nombre: String) {
        val valores = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, nombre)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AVIaMP4")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val destino = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, valores)
            ?: throw IllegalStateException("MediaStore no creó el archivo")
        try {
            contentResolver.openOutputStream(destino).use { salida ->
                requireNotNull(salida) { "No se abrió la salida" }
                archivo.inputStream().use { it.copyTo(salida, 1 shl 20) }
            }
            valores.clear()
            valores.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(destino, valores, null, null)
        } catch (e: Exception) {
            contentResolver.delete(destino, null, null)
            throw e
        }
    }

    private fun terminar() {
        ocupado = false
        saltarBtn.visibility = android.view.View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (total == 0) return
        estado.text = buildString {
            append("Listo: $hechos convertido(s)")
            if (fallidos > 0) append(", $fallidos con error")
            append(".\nGuardados en Películas/AVIaMP4\n\n")
            append(registro)
        }
    }

    private fun nombreDe(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    override fun onDestroy() {
        destruida = true
        handler.removeCallbacks(progreso)
        runCatching { transformer?.cancel() }
        proceso?.destroy()
        io.shutdown()
        super.onDestroy()
    }
}
