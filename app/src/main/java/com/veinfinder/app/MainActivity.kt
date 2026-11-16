package com.veinfinder.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var thumb: ImageView
    private lateinit var spinner: Spinner
    private lateinit var btnStart: TextView
    private lateinit var btnEnhance: TextView
    private lateinit var btnGreen: TextView
    private lateinit var btnNegative: TextView

    private val processor = VeinProcessor()
    private var source: FrameSource? = null
    private var cameras: List<CameraEntry> = emptyList()
    private val busy = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var showOriginal = false
    @Volatile private var mode = ViewMode.GRAY
    @Volatile private var lastOrig: Bitmap? = null
    @Volatile private var lastProc: Bitmap? = null
    private var lastSavedUri: Uri? = null
    private var activeCameraId: String? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (!OpenCVLoader.initLocal()) toast("OpenCV failed to load")

        imageView = findViewById(R.id.imageView)
        statusText = findViewById(R.id.statusText)
        thumb = findViewById(R.id.thumb)
        spinner = findViewById(R.id.cameraSpinner)
        btnStart = findViewById(R.id.btnStart)
        btnEnhance = findViewById(R.id.btnEnhance)
        btnGreen = findViewById(R.id.btnGreen)
        btnNegative = findViewById(R.id.btnNegative)

        val prefs = getSharedPreferences("vein", Context.MODE_PRIVATE)
        processor.k = prefs.getFloat("k", 0.9f).toDouble()
        processor.b = prefs.getFloat("b", 10f).toDouble()

        refreshCameraList()

        btnStart.setOnClickListener {
            if (running) stopCamera() else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    startCamera() else permission.launch(Manifest.permission.CAMERA)
            }
        }
        btnEnhance.setOnClickListener {
            if (!requireRunning()) return@setOnClickListener
            showOriginal = !showOriginal
            btnEnhance.text = if (showOriginal) "Original" else "Enhance"
        }
        btnGreen.setOnClickListener {
            if (!requireRunning()) return@setOnClickListener
            if (mode == ViewMode.GREEN) { mode = ViewMode.GRAY; btnGreen.text = "Green" }
            else { mode = ViewMode.GREEN; btnGreen.text = "Gray" }
            btnNegative.text = "Positive"
            showOriginal = false; btnEnhance.text = "Enhance"
        }
        btnNegative.setOnClickListener {
            if (!requireRunning()) return@setOnClickListener
            if (mode == ViewMode.NEGATIVE) { mode = ViewMode.GRAY; btnNegative.text = "Positive" }
            else { mode = ViewMode.NEGATIVE; btnNegative.text = "Negative" }
            btnGreen.text = "Green"
            showOriginal = false; btnEnhance.text = "Enhance"
        }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveImage() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        thumb.setOnClickListener {
            lastSavedUri?.let {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // switching camera while running (ignore the initial automatic selection callback)
                if (running && cameras.getOrNull(pos)?.id != activeCameraId) { stopCamera(); startCamera() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ---------------------------------------------------------------- cameras
    private fun refreshCameraList() {
        val list = ArrayList<CameraEntry>()
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                val label = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Phone back camera ($id)"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Phone front camera ($id)"
                    else -> "External camera ($id)"
                }
                list.add(CameraEntry(label, false, id))
            }
        } catch (_: Exception) {}
        val um = getSystemService(Context.USB_SERVICE) as UsbManager
        um.deviceList.values.filter { isUvc(it) }.forEach {
            list.add(CameraEntry("USB: ${it.productName ?: it.deviceName}", true, it.deviceName))
        }
        val prevId = cameras.getOrNull(spinner.selectedItemPosition)?.id   // keep the user's choice
        cameras = list
        val adapter = ArrayAdapter(this, R.layout.spinner_item, list.map { it.label })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter
        val idx = list.indexOfFirst { it.id == prevId }
        if (idx >= 0) spinner.setSelection(idx)
    }

    private fun isUvc(d: UsbDevice): Boolean {
        if (d.deviceClass == UsbConstants.USB_CLASS_VIDEO || d.deviceClass == 239) return true
        for (i in 0 until d.interfaceCount) if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!running) refreshCameraList()           // USB camera plugged in
    }

    override fun onResume() { super.onResume(); if (!running) refreshCameraList() }

    private fun startCamera() {
        val entry = cameras.getOrNull(spinner.selectedItemPosition)
        if (entry == null) { toast("No camera found"); return }
        val src: FrameSource = if (entry.usb) {
            val um = getSystemService(Context.USB_SERVICE) as UsbManager
            val dev = um.deviceList[entry.id] ?: run { toast("USB camera not found"); return }
            UvcCameraSource(applicationContext, dev)
        } else PhoneCameraSource(applicationContext, this, entry.id)
        source = src
        activeCameraId = entry.id
        running = true
        btnStart.text = "Stop"; btnGreen.text = "Green"; btnNegative.text = "Positive"; btnEnhance.text = "Enhance"
        mode = ViewMode.GRAY; showOriginal = false
        statusText.visibility = View.GONE
        src.start({ frame -> onFrame(frame) }, { msg -> runOnUiThread { toast(msg); stopCamera() } })
    }

    private fun stopCamera() {
        running = false
        source?.stop(); source = null
        btnStart.text = "Start"
        statusText.visibility = View.VISIBLE
    }

    override fun onStop() { super.onStop(); if (running) stopCamera() }

    // ---------------------------------------------------------------- frames
    private fun onFrame(frame: Mat) {
        if (!running || !busy.compareAndSet(false, true)) { frame.release(); return }
        try {
            var src = frame
            if (frame.width() > 640) {                                  // keep the same scale the Windows app used
                val s = Mat()
                val ratio = 640.0 / frame.width()
                Imgproc.resize(frame, s, Size(640.0, frame.height() * ratio))
                frame.release(); src = s
            }
            val orig = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(src, orig)
            val out = processor.process(src, mode)
            val proc = Bitmap.createBitmap(out.width(), out.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, proc)
            src.release(); out.release()
            lastOrig = orig; lastProc = proc
            val shown = if (showOriginal) orig else proc
            runOnUiThread { imageView.setImageBitmap(shown) }
        } catch (e: Exception) {
            runOnUiThread { toast(e.message ?: "Processing error") }
        } finally { busy.set(false) }
    }

    private fun requireRunning(): Boolean {
        if (!running) { toast("Please click the start..."); return false }
        return true
    }

    // ---------------------------------------------------------------- save
    private fun saveImage() {
        val bmp = (if (showOriginal) lastOrig else lastProc)
        if (!running || bmp == null) { AlertDialog.Builder(this).setTitle("Kindly reminder")
            .setMessage("Please open the camera").setPositiveButton("OK", null).show(); return }
        val name = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()) + ".jpg"
        try {
            val uri: Uri?
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Veinfinder")
                }
                uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                uri?.let { contentResolver.openOutputStream(it)?.use { os -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, os) } }
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Veinfinder").apply { mkdirs() }
                val f = File(dir, name)
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            }
            lastSavedUri = uri
            thumb.setImageBitmap(bmp)
            toast("Save success: Pictures/Veinfinder/$name")
        } catch (e: Exception) { toast("Save failed: ${e.message}") }
    }

    // ---------------------------------------------------------------- settings (k and b from the .config file)
    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val kLabel = TextView(this); val bLabel = TextView(this)
        val kBar = SeekBar(this).apply { max = 100; progress = (processor.k * 100).toInt() }   // 0..1
        val bBar = SeekBar(this).apply { max = 100; progress = (processor.b * 10).toInt() }    // 0..10
        fun upd() {
            kLabel.text = String.format(Locale.US, "k (0 - 1):  %.2f", kBar.progress / 100.0)
            bLabel.text = String.format(Locale.US, "b (0 - 10):  %.1f", bBar.progress / 10.0)
        }
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                processor.k = kBar.progress / 100.0; processor.b = bBar.progress / 10.0; upd()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        kBar.setOnSeekBarChangeListener(l); bBar.setOnSeekBarChangeListener(l); upd()
        root.addView(kLabel); root.addView(kBar); root.addView(bLabel); root.addView(bBar)
        AlertDialog.Builder(this).setTitle("Settings").setView(root)
            .setPositiveButton("OK") { _, _ ->
                getSharedPreferences("vein", Context.MODE_PRIVATE).edit()
                    .putFloat("k", processor.k.toFloat()).putFloat("b", processor.b.toFloat()).apply()
            }
            .setNeutralButton("Reset") { _, _ ->
                processor.k = 0.9; processor.b = 10.0
                getSharedPreferences("vein", Context.MODE_PRIVATE).edit().putFloat("k", 0.9f).putFloat("b", 10f).apply()
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** One entry of the camera spinner. [usb] = UVC webcam (id = USB device name), otherwise Camera2 id. */
data class CameraEntry(val label: String, val usb: Boolean, val id: String)
