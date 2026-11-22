package com.sebas.avimp4

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.activity.result.contract.ActivityResultContracts
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

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var estado: TextView
    private lateinit var barra: ProgressBar
    private lateinit var boton: Button

    private val cola = ArrayDeque<Uri>()
    private var total = 0
    private var hechos = 0
    private var fallidos = 0
    private var ocupado = false
    private val registro = StringBuilder()

    private var transformer: Transformer? = null
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
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                barra.progress = holder.progress
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estado = findViewById(R.id.estado)
        barra = findViewById(R.id.barra)
        boton = findViewById(R.id.boton)
        boton.setOnClickListener { elegir.launch(arrayOf("video/*")) }
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

    private fun siguiente() {
        val uri = cola.removeFirstOrNull()
        if (uri == null) {
            terminar()
            return
        }
        ocupado = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val nombre = nombreDe(uri)
        val salida = nombre.substringBeforeLast('.', nombre) + ".mp4"
        val tmp = File(cacheDir, "conv_${System.currentTimeMillis()}.mp4")
        estado.text = "Convirtiendo ${hechos + fallidos + 1} de $total\n$nombre\n\n$registro"
        barra.progress = 0

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    barra.progress = 100
                    estado.text = "Guardando $salida…\n\n$registro"
                    guardar(tmp, salida, nombre)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    transformer = null
                    tmp.delete()
                    fallidos++
                    registro.append("✗ $nombre\n   ${exportException.errorCodeName}\n")
                    siguiente()
                }
            })
            .build()

        transformer = t
        try {
            t.start(MediaItem.fromUri(uri), tmp.absolutePath)
            handler.post(progreso)
        } catch (e: Exception) {
            transformer = null
            fallidos++
            registro.append("✗ $nombre\n   ${e.message}\n")
            siguiente()
        }
    }

    private fun guardar(tmp: File, salida: String, original: String) {
        io.execute {
            val error = runCatching { copiarAGaleria(tmp, salida) }.exceptionOrNull()
            tmp.delete()
            runOnUiThread {
                if (error == null) {
                    hechos++
                    registro.append("✓ $salida\n")
                } else {
                    fallidos++
                    registro.append("✗ $original\n   No se pudo guardar: ${error.message}\n")
                }
                siguiente()
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
        handler.removeCallbacks(progreso)
        transformer?.cancel()
        io.shutdown()
        super.onDestroy()
    }
}
