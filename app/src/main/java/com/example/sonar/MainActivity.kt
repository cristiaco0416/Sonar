package com.example.sonar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

const val FS = 48000              // frecuencia de muestreo
const val SOUND_SPEED = 343.0     // m/s (aprox. a 20 °C)
const val MAX_RANGE = 6.0         // alcance máximo mostrado (m)
const val CHIRP_MS = 12           // duración del pitido
const val WINDOW_S = 0.30         // tiempo de escucha tras cada pitido

class SonarView(c: Context) : View(c) {
    @Volatile var data: FloatArray? = null
    @Volatile var marker = -1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        cv.drawColor(Color.BLACK)
        p.strokeWidth = 2f
        p.textSize = 28f
        for (m in 1..5) {
            val x = w * m / MAX_RANGE.toFloat()
            p.color = Color.rgb(0, 90, 0)
            cv.drawLine(x, 0f, x, h, p)
            p.color = Color.WHITE
            cv.drawText("${m} m", x + 4f, h - 6f, p)
        }
        val d = data
        if (d != null && d.size > 2) {
            val start = (FS * 0.002).toInt()
            var mx = 1e-9f
            for (i in start until d.size) if (d[i] > mx) mx = d[i]
            p.color = Color.GREEN
            p.strokeWidth = 3f
            for (i in start + 1 until d.size) {
                val x0 = w * (i - 1) / d.size
                val x1 = w * i / d.size
                val y0 = h - 40f - (h - 80f) * d[i - 1] / mx
                val y1 = h - 40f - (h - 80f) * d[i] / mx
                cv.drawLine(x0, y0, x1, y1, p)
            }
        }
        if (marker > 0f) {
            p.color = Color.RED
            p.strokeWidth = 4f
            val x = w * marker / MAX_RANGE.toFloat()
            cv.drawLine(x, 0f, x, h, p)
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var btn: Button
    private lateinit var ultra: Switch
    private lateinit var distText: TextView
    private lateinit var status: TextView
    private lateinit var sonarView: SonarView
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        ultra = Switch(this).apply { text = "Modo ultrasónico (casi silencioso)  " }
        btn = Button(this).apply { text = "INICIAR" }
        distText = TextView(this).apply { textSize = 48f; text = "-- m" }
        status = TextView(this).apply { text = "Sube el volumen al máximo y apunta a una pared" }
        sonarView = SonarView(this)
        root.addView(ultra)
        root.addView(btn)
        root.addView(distText)
        root.addView(sonarView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 650))
        root.addView(status)
        setContentView(root)

        btn.setOnClickListener {
            if (running) stopSonar()
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startSonar()
            else ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onPause() { super.onPause(); stopSonar() }

    private fun startSonar() {
        val f0 = if (ultra.isChecked) 16000.0 else 2000.0
        val f1 = if (ultra.isChecked) 21000.0 else 8000.0
        running = true
        btn.text = "DETENER"
        Thread { runSonar(f0, f1) }.start()
    }

    private fun stopSonar() { running = false; btn.text = "INICIAR" }

    private fun runSonar(f0: Double, f1: Double) {
        // ---- pitido (chirp) y sus dos referencias para correlación ----
        val m = FS * CHIRP_MS / 1000
        val refI = FloatArray(m)
        val refQ = FloatArray(m)
        val chirp = ShortArray(m)
        val k = (f1 - f0) / (m.toDouble() / FS)
        for (i in 0 until m) {
            val t = i.toDouble() / FS
            val w = 0.5 - 0.5 * cos(2 * PI * i / (m - 1))     // ventana Hann
            val ph = 2 * PI * (f0 * t + 0.5 * k * t * t)
            refI[i] = (w * cos(ph)).toFloat()
            refQ[i] = (w * sin(ph)).toFloat()
            chirp[i] = (0.9 * 32767 * w * cos(ph)).toInt().toShort()
        }

        // ---- audio ----
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val src = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val recBuf = max(AudioRecord.getMinBufferSize(FS,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FS * 2)
        val rec = AudioRecord(src, FS, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, recBuf)
        val trkBuf = max(AudioTrack.getMinBufferSize(FS,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), m * 8)
        val trk = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(FS)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(trkBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); trk.release(); running = false
            runOnUiThread { status.text = "No se pudo abrir el micrófono"; btn.text = "INICIAR" }
            return
        }

        val n = (FS * WINDOW_S).toInt()
        val len = n - m + 1
        val kmax = (FS * 2 * MAX_RANGE / SOUND_SPEED).toInt()
        val excl = (FS * 0.002).toInt()          // zona ciega tras el sonido directo (~35 cm)
        val buf = ShortArray(n)
        val tmp = ShortArray(4096)
        val x = FloatArray(n)
        val env = FloatArray(len)
        val hist = ArrayDeque<FloatArray>()

        try {
            rec.startRecording()
            trk.play()
            while (running) {
                // vaciar lo que haya grabado antes de emitir
                while (rec.read(tmp, 0, tmp.size, AudioRecord.READ_NON_BLOCKING) > 0) { }
                trk.write(chirp, 0, m)                       // ¡pitido!
                var got = 0
                while (got < n && running) {
                    val r = rec.read(buf, got, n - got)
                    if (r <= 0) break
                    got += r
                }
                if (got < n) break

                for (i in 0 until n) x[i] = buf[i] / 32768f
                // correlación (filtro adaptado) -> envolvente
                for (i in 0 until len) {
                    var a = 0f
                    var b = 0f
                    for (j in 0 until m) {
                        val v = x[i + j]
                        a += v * refI[j]
                        b += v * refQ[j]
                    }
                    env[i] = sqrt(a * a + b * b)
                }
                // sonido directo altavoz->micrófono = el pico más fuerte (referencia de tiempo 0)
                var d = 0
                for (i in 1 until len) if (env[i] > env[d]) d = i
                val sorted = env.copyOf().also { it.sort() }
                val med = sorted[len / 2]
                if (env[d] < 10f * med || d + kmax > len) {
                    runOnUiThread { status.text = "No oigo el pitido: sube el volumen" }
                    Thread.sleep(150)
                    continue
                }
                // promedio de los últimos 4 pitidos, alineados con el sonido directo
                hist.addLast(FloatArray(kmax) { env[d + it] / env[d] })
                if (hist.size > 4) hist.removeFirst()
                val avg = FloatArray(kmax)
                for (h in hist) for (i in 0 until kmax) avg[i] += h[i] / hist.size

                // primer eco fuerte = pared más cercana
                var pk = excl
                for (i in excl until kmax) if (avg[i] > avg[pk]) pk = i
                val seg = avg.copyOfRange(excl, kmax).also { it.sort() }
                val noise = seg[seg.size / 2]
                var dist = -1.0
                if (avg[pk] > 6f * noise && avg[pk] > 0.002f) {
                    val thr = max(6f * noise, 0.5f * avg[pk])
                    var i = excl
                    while (i < kmax - 1 && avg[i] < thr) i++
                    while (i < kmax - 1 && avg[i + 1] >= avg[i]) i++
                    dist = i.toDouble() / FS * SOUND_SPEED / 2.0
                }
                val shown = dist
                runOnUiThread {
                    sonarView.data = avg
                    sonarView.marker = shown.toFloat()
                    sonarView.invalidate()
                    distText.text = if (shown > 0) "%.2f m".format(shown) else "-- m"
                    status.text = if (shown > 0) "Eco detectado" else "Sin eco claro"
                }
                Thread.sleep(80)
            }
        } catch (e: Exception) {
            runOnUiThread { status.text = "Error: ${e.message}" }
        } finally {
            try { rec.stop() } catch (_: Exception) { }
            rec.release()
            try { trk.stop() } catch (_: Exception) { }
            trk.release()
            running = false
            runOnUiThread { btn.text = "INICIAR" }
        }
    }
}
