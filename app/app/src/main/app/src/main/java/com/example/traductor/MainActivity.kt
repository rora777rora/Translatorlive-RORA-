package com.example.traductor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val languages = listOf(
        "Ruso" to "ru", "Ucraniano" to "uk", "Chino" to "zh", "Japonés" to "ja"
    )
    private lateinit var spinner: Spinner
    private val reqPerms = 1
    private val reqProjection = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Idioma del audio (se traduce al inglés):"
            textSize = 18f
        })
        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                languages.map { it.first }
            )
        }
        root.addView(spinner)
        root.addView(Button(this).apply {
            text = "Iniciar"
            setOnClickListener { begin() }
        })
        root.addView(Button(this).apply {
            text = "Detener"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction("STOP"))
            }
        })
        root.addView(TextView(this).apply {
            text = "La primera vez se descargan el modelo de voz (~50 MB) y el " +
                "traductor. Hace falta internet solo esa vez."
        })
        setContentView(root)
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activa 'Mostrar sobre otras apps' y vuelve a pulsar Iniciar", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), reqPerms)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == reqPerms && results.all { it == PackageManager.PERMISSION_GRANTED }) begin()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection && resultCode == RESULT_OK && data != null) {
            val lang = languages[spinner.selectedItemPosition].second
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("lang", lang)
            startForegroundService(i)
        }
    }
}
