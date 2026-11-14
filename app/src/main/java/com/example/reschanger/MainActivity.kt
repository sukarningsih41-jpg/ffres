package com.example.reschanger

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQ_SHIZUKU = 1001
        const val MIN_W = 240
        const val STEP = 10
        const val MIN_SCALE = 0.3f   // batas bawah faktor skala Game Mode
        const val MAX_SCALE = 1.0f
    }

    private lateinit var tvShizuku: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvLog: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var rbMax: RadioButton

    private var physW = 0
    private var physH = 0

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) loadPhysicalInfo()
            updateShizukuStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvShizuku = findViewById(R.id.tvShizuku)
        tvInfo = findViewById(R.id.tvInfo)
        tvTarget = findViewById(R.id.tvTarget)
        tvLog = findViewById(R.id.tvLog)
        seekBar = findViewById(R.id.seekBar)
        rbMax = findViewById(R.id.rbMax)

        Shizuku.addRequestPermissionResultListener(permListener)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                refreshTargetLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn240).setOnClickListener { setWidth(240) }
        findViewById<Button>(R.id.btn480).setOnClickListener { setWidth(480) }
        findViewById<Button>(R.id.btn720).setOnClickListener { setWidth(720) }
        findViewById<Button>(R.id.btnApply).setOnClickListener { applyToGame() }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { checkGame() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetGame() }

        updateShizukuStatus()
        if (ensurePermission()) loadPhysicalInfo()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun pkg() = if (rbMax.isChecked) "com.dts.freefiremax" else "com.dts.freefireth"

    private fun setWidth(w: Int) {
        seekBar.progress = (w - MIN_W) / STEP
    }

    private fun targetWidth() = MIN_W + seekBar.progress * STEP

    /** Faktor skala = lebar target / lebar layar asli, dibatasi 0.3..1.0 */
    private fun scaleFor(w: Int): Float {
        val base = if (physW > 0) physW else 1080
        return (w.toFloat() / base).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun refreshTargetLabel() {
        val w = targetWidth()
        val raw = w.toFloat() / (if (physW > 0) physW else 1080)
        val s = scaleFor(w)
        val note = if (raw < MIN_SCALE) " (dibatasi minimum)" else ""
        tvTarget.text = "Target: $w  ->  skala " + String.format(Locale.US, "%.2f", s) + note
    }

    // ---------- Shizuku ----------

    private fun shizukuReady(): Boolean =
        try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }

    private fun updateShizukuStatus() {
        tvShizuku.text = try {
            when {
                !Shizuku.pingBinder() -> "Status Shizuku: BELUM AKTIF (buka app Shizuku & start)"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    "Status Shizuku: aktif, izin belum diberikan"
                else -> "Status Shizuku: SIAP"
            }
        } catch (e: Throwable) {
            "Status Shizuku: tidak terpasang"
        }
    }

    private fun ensurePermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            Shizuku.requestPermission(REQ_SHIZUKU)
            false
        } catch (e: Throwable) {
            false
        }
    }

    private fun runShell(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        return (out + err).trim()
    }

    // ---------- Logika ----------

    private fun loadPhysicalInfo() {
        thread {
            try {
                val size = runShell("wm size")
                val s = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(size)
                if (s != null) {
                    val a = s.groupValues[1].toInt()
                    val b = s.groupValues[2].toInt()
                    physW = minOf(a, b)
                    physH = maxOf(a, b)
                }
                runOnUiThread {
                    tvInfo.text = "Layar asli: ${physW}x${physH}\nHanya Free Fire yang diubah, layar HP tetap normal."
                    refreshTargetLabel()
                }
            } catch (e: Throwable) {
                runOnUiThread { tvLog.text = "Gagal baca info layar: ${e.message}" }
            }
        }
    }

    private fun requireReady(): Boolean {
        if (!shizukuReady()) {
            Toast.makeText(this, "Shizuku belum siap", Toast.LENGTH_SHORT).show()
            ensurePermission()
            return false
        }
        return true
    }

    private fun applyToGame() {
        if (!requireReady()) return
        val scale = String.format(Locale.US, "%.2f", scaleFor(targetWidth()))
        val p = pkg()
        thread {
            try {
                val r = runShell("cmd game set --mode performance --downscale $scale $p; cmd game list-configs $p")
                runOnUiThread {
                    tvLog.text = "Perintah: set --downscale $scale $p\n\n$r\n\nTutup total lalu buka ulang Free Fire agar berlaku."
                }
            } catch (e: Throwable) {
                runOnUiThread { tvLog.text = "Gagal: ${e.message}" }
            }
        }
    }

    private fun checkGame() {
        if (!requireReady()) return
        val p = pkg()
        thread {
            try {
                val r = runShell("cmd game list-configs $p")
                runOnUiThread { tvLog.text = r.ifEmpty { "(tidak ada output - HP mungkin tidak mendukung Game Mode)" } }
            } catch (e: Throwable) {
                runOnUiThread { tvLog.text = "Gagal: ${e.message}" }
            }
        }
    }

    private fun resetGame() {
        if (!requireReady()) return
        val p = pkg()
        thread {
            try {
                val r = runShell("cmd game reset $p")
                runOnUiThread { tvLog.text = "Game dikembalikan ke pengaturan normal.\n$r" }
            } catch (e: Throwable) {
                runOnUiThread { tvLog.text = "Gagal reset: ${e.message}" }
            }
        }
    }
}
