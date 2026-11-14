package com.hqrec.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val resNames = arrayOf("4K (3840)", "2K (2560)", "1080p (1920)", "720p (1280)")
    private val resLong = intArrayOf(3840, 2560, 1920, 1280)
    private val qualityNames = arrayOf("Normal", "High (best balance)", "Ultra (bahut bada file)")
    private val qualityFactor = doubleArrayOf(0.10, 0.16, 0.25)
    private val audioNames = arrayOf("Game sound + Mic", "Sirf Game sound", "Sirf Mic", "Audio nahi")

    private lateinit var spRes: Spinner
    private lateinit var spFps: Spinner
    private lateinit var spQuality: Spinner
    private lateinit var spAudio: Spinner
    private lateinit var spOrient: Spinner
    private lateinit var swHevc: Switch
    private lateinit var btn: Button

    private fun spinner(items: Array<String>, default: Int): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(default)
        return s
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, 28, 0, 4)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "HQ Recorder"; textSize = 28f; setTextColor(Color.BLACK)
            gravity = Gravity.START
        })
        root.addView(TextView(this).apply {
            text = "Game recording - hardware encoder, bina lag"; textSize = 13f
        })

        spRes = spinner(resNames, 2);          root.addView(label("Resolution")); root.addView(spRes)
        spFps = spinner(arrayOf("30 FPS (low phones ke liye best)", "60 FPS (smooth)"), 0)
        root.addView(label("FPS")); root.addView(spFps)
        spQuality = spinner(qualityNames, 1);  root.addView(label("Quality (bitrate)")); root.addView(spQuality)
        spAudio = spinner(audioNames, 0);      root.addView(label("Audio")); root.addView(spAudio)
        spOrient = spinner(arrayOf("Landscape (games)", "Portrait"), 0)
        root.addView(label("Orientation")); root.addView(spOrient)

        swHevc = Switch(this).apply { text = "H.265 (HEVC): chhoti file, wahi quality" }
        root.addView(label("Codec")); root.addView(swHevc)

        btn = Button(this).apply {
            textSize = 18f
            setOnClickListener { onButton() }
        }
        root.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 56 })

        root.addView(TextView(this).apply {
            text = "Tips:\n- START dabane ke baad app band ho jayegi, game kholo.\n" +
                "- Rokne ke liye notification me STOP dabao.\n" +
                "- Game sound ke liye Android 10+ chahiye.\n" +
                "- Video Movies/HQRecorder folder me save hogi."
            textSize = 12f; setPadding(0, 40, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        btn.text = if (RecorderService.recording) "STOP RECORDING" else "START RECORDING"
    }

    private fun onButton() {
        if (RecorderService.recording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            btn.text = "START RECORDING"
            return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else askProjection()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        askProjection()
    }

    private fun askProjection() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK || data == null) return

        val cfg = buildConfig() ?: return
        val i = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra("code", resultCode)
            putExtra("data", data)
            putExtra("w", cfg.width); putExtra("h", cfg.height); putExtra("dpi", cfg.dpi)
            putExtra("fps", cfg.fps); putExtra("br", cfg.bitrate)
            putExtra("audio", cfg.audioMode.ordinal)
            putExtra("hevc", cfg.hevc); putExtra("codec", cfg.codecName)
        }
        startForegroundService(i)
        Toast.makeText(this, "${cfg.width}x${cfg.height} @${cfg.fps}fps, ${cfg.bitrate / 1_000_000} Mbps", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun buildConfig(): RecConfig? {
        val hevc = swHevc.isChecked
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val info = Caps.pick(mime)
        if (info == null) {
            Toast.makeText(this, "Is phone me ye codec support nahi hai", Toast.LENGTH_LONG).show()
            return null
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val aspect = minOf(dm.widthPixels, dm.heightPixels).toFloat() /
            maxOf(dm.widthPixels, dm.heightPixels)
        val landscape = spOrient.selectedItemPosition == 0
        val wantFps = if (spFps.selectedItemPosition == 0) 30 else 60

        var size: Pair<Int, Int>? = null
        var fps = wantFps
        var idx = spRes.selectedItemPosition
        loop@ while (idx < resLong.size) {
            for (f in intArrayOf(wantFps, 30).distinct()) {
                size = Caps.fit(info, mime, resLong[idx], aspect, f, landscape)
                if (size != null) { fps = f; break@loop }
            }
            idx++
        }
        if (size == null) {
            Toast.makeText(this, "Koi resolution support nahi mili", Toast.LENGTH_LONG).show()
            return null
        }
        if (idx != spRes.selectedItemPosition || fps != wantFps) {
            Toast.makeText(
                this,
                "Phone ka encoder ${resNames[spRes.selectedItemPosition]} ${wantFps}fps support nahi karta. " +
                    "${resNames[idx]} ${fps}fps use ho raha hai.",
                Toast.LENGTH_LONG
            ).show()
        }

        val factor = qualityFactor[spQuality.selectedItemPosition] * (if (hevc) 0.65 else 1.0)
        val br = minOf(
            (size.first.toLong() * size.second * fps * factor).toLong(),
            Caps.maxBitrate(info, mime).toLong()
        ).toInt()

        val audio = AudioMode.values()[spAudio.selectedItemPosition]
        return RecConfig(size.first, size.second, dm.densityDpi, fps, br, audio, hevc, info.name)
    }
}
