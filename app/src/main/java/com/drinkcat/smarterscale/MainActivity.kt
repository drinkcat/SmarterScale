package com.drinkcat.smarterscale

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MenuInflater
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.collection.CircularArray
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.iterator
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Objects
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.sqrt


class MainActivity : ComponentActivity(), CvCameraViewListener2 {
    /* UI elements */
    private lateinit var mVersion: TextView
    private lateinit var mOpenCvCameraView: SmarterCameraView
    private lateinit var mHelp: TextView
    private lateinit var mWeight: TextView
    private lateinit var mWeightUnit: TextView
    private lateinit var mStartStop: Button
    private lateinit var mSubmit: Button

    private lateinit var mDigitizer: Digitizer
    private var mSmarterHealthConnect = SmarterHealthConnect(this)

    /* output color frame that includes drawn shapes. */
    private lateinit var outputFull: Mat

    /* Debug only: save 9 input images (for display it's nicer to pick a square number). */
    private val INPUT_DEBUG_SAVE_COUNT = 9
    private var mInputDebugSave = CircularArray<Mat>(INPUT_DEBUG_SAVE_COUNT)

    /* State */
    private var started = false
    private var readWeight: Double? = null

    /* State to save as preferences */
    private var unit = WeightUnit.KG
    private var autoSubmit = false
    private var showHelp = true
    private var debug = true
    private var initZoom = 0.0

    /* Initialization functions */
    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "called onCreate")
        super.onCreate(savedInstanceState)

        if (!openCVInit()) return
        outputFull = Mat()
        mSmarterHealthConnect.init()

        mDigitizer = Digitizer(com.drinkcat.smarterscale.BuildConfig.DEBUG)

        setupBasicLayout()
        setupEventListeners()

        createRequestCameraPermissions()

        readPreferences()
        refreshUI()
    }

    private fun openCVInit(): Boolean {
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
            return true
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show()
            return false
        }
    }

    private fun setupBasicLayout() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        this.enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.main)
        ) { v: View, insets: WindowInsetsCompat ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mOpenCvCameraView =
            findViewById<View>(R.id.main_activity_smarter_camera_view) as SmarterCameraView
        mOpenCvCameraView.setCvCameraViewListener(this)

        mVersion = findViewById<View>(R.id.main_activity_version) as TextView
        mHelp = findViewById<View>(R.id.main_activity_help) as TextView
        mWeight = findViewById<View>(R.id.main_activity_weight) as TextView
        mWeightUnit = findViewById<View>(R.id.main_activity_weight_unit) as TextView

        mStartStop = findViewById<View>(R.id.main_activity_start_stop) as Button
        mSubmit = findViewById<View>(R.id.main_activity_submit) as Button
        mSubmit.isEnabled = false

        refreshUI()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupEventListeners() {
        val scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var beginSpan = 0f
                private var beginZoom = 1.0

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    beginSpan = detector.currentSpan
                    beginZoom = mOpenCvCameraView.zoom
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!beginZoom.isFinite() || beginSpan == 0f) return true
                    val span = detector.currentSpan
                    val newZoom = beginZoom + (span - beginSpan) / beginSpan
                    Log.d(
                        TAG,
                        "onScale zoom $beginSpan/$span => $newZoom($beginZoom"
                    )
                    /* TODO: Manually crop input more if newZoom > 1.0. */
                    mOpenCvCameraView.zoom = newZoom
                    return true
                }
            })
        mOpenCvCameraView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
        }

        mStartStop.setOnClickListener { startStop(!started) }
        mSubmit.setOnClickListener { submitWeight() }

        val menuListener = PopupMenu.OnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_unit_kg -> { unit = WeightUnit.KG; refreshUI(); true }
                R.id.menu_unit_lb -> { unit = WeightUnit.LB; refreshUI(); true }
                R.id.menu_debug -> { debug = !item.isChecked; refreshUI(); true }
                R.id.menu_auto -> { autoSubmit = !item.isChecked; refreshUI(); true }
                R.id.menu_showhelp -> { showHelp = !item.isChecked; refreshUI(); true }
                R.id.menu_privacy -> {
                    startActivity(Intent(this, PermissionsRationaleActivity::class.java))
                    true
                }
                R.id.menu_savedebug -> {
                    saveDebugImages()
                    true
                }
                else -> { false }
            }
        }

        findViewById<Button>(R.id.dropdown_menu).setOnClickListener {
            val popup = PopupMenu(this, it)
            val inflater: MenuInflater = popup.menuInflater
            inflater.inflate(R.menu.main_menu, popup.menu)
            for (item in popup.menu.iterator()) {
                when (item.itemId) {
                    R.id.menu_unit_kg -> if (unit == WeightUnit.KG) item.setChecked(true)
                    R.id.menu_unit_lb -> if (unit == WeightUnit.LB) item.setChecked(true)
                    R.id.menu_debug -> item.setChecked(debug)
                    R.id.menu_auto -> item.setChecked(autoSubmit)
                    R.id.menu_showhelp -> item.setChecked(showHelp)
                    R.id.menu_savedebug -> item.setVisible(debug)
                }
            }
            popup.setOnMenuItemClickListener(menuListener)
            popup.show()
        }
    }

    private lateinit var mRequestPermission: ActivityResultLauncher<String>
    private fun createRequestCameraPermissions() {
        mRequestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            isGranted ->
                if (isGranted) {
                    Log.d(TAG, "Camera permissions granted!")
                    mOpenCvCameraView.setCameraPermissionGranted()
                } else {
                    Log.d(TAG, "Camera permissions denied!")
                    Toast.makeText(this, "Camera permissions denied!", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun readPreferences() {
        val pref = getPreferences(MODE_PRIVATE)
        unit = WeightUnit.fromString(pref.getString("unit", "kg")!!)
        autoSubmit = pref.getBoolean("autoSubmit", false)
        showHelp = pref.getBoolean("showHelp", true)
        debug = pref.getBoolean("debug", false)
        initZoom = pref.getFloat("initZoom", 0.0f).toDouble()
    }
    /* end of init functions */

    private fun writePreferences() {
        val pref = getPreferences(MODE_PRIVATE)
        with (pref.edit()) {
            putBoolean("autoSubmit", autoSubmit)
            putBoolean("showHelp", showHelp)
            putBoolean("debug", debug)
            putFloat("initZoom", initZoom.toFloat())
            apply()
        }
    }

    public override fun onStart() {
        Log.d(TAG, "onStart")
        super.onStart()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            mOpenCvCameraView.setCameraPermissionGranted()
        else
            mRequestPermission.launch(Manifest.permission.CAMERA)
    }

    public override fun onPause() {
        Log.d(TAG, "onPause")
        super.onPause()
        startStop(false)
        writePreferences()
    }

    public override fun onResume() {
        Log.d(TAG, "onResume")
        super.onResume()
        // TODO: There's a bug here, and I don't know how to fix this cleanly:
        // When requesting permission, a new intent is created, and I don't know
        // how to detect that I'm coming back from that (and _not_ start camera
        // capture). If I test if mWeight != null, this fixes the issue, but
        // then the camera doesn't auto-start when the app is "restarted".
        startStop(true)
    }

    public override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        startStop(false)
    }

    private fun submitWeight() {
        if (readWeight == null || !readWeight!!.isFinite())
            return
        mSubmit.isEnabled = false
        lifecycle.coroutineScope.launch {
            mSmarterHealthConnect.writeWeightInput(readWeight!!, unit)
        }
    }

    /* Refresh UI elements depending on state. */
    private fun refreshUI() {
        /* Text content */
        mStartStop.text = (
            if (started) "Stop"
            else if (readWeight != null) "Restart"
            else "Start"
                )
        mWeight.text = (
            if (readWeight == null) "??.?"
            else if (readWeight!!.isFinite()) readWeight!!.toString()
            else "BAD"
                )
        mWeightUnit.text = unit.toString()

        /* Visibility */
        fun boolToVisible(b: Boolean): Int = if (b) View.VISIBLE else View.INVISIBLE

        mVersion.visibility = boolToVisible(debug)
        val weightVisible = readWeight != null || !showHelp
        mWeight.visibility = boolToVisible(weightVisible)
        mWeightUnit.visibility = boolToVisible(weightVisible)
        mHelp.visibility = boolToVisible(!weightVisible)
        mStartStop.visibility = boolToVisible(debug || !started)
        mSubmit.visibility = boolToVisible(!autoSubmit)
        if (started) {
            mOpenCvCameraView.enableView()
            if (debug)
                mOpenCvCameraView.enableFpsMeter()
            else
                mOpenCvCameraView.disableFpsMeter()
        }
        else
            mOpenCvCameraView.disableView()
    }

    private fun startStop(start: Boolean) {
        started = start
        if (start) {
            mSubmit.isEnabled = false
            readWeight = null
            mDigitizer.reset()
            while (mInputDebugSave.size() > 0)
                mInputDebugSave.popFirst().release()
        }
        refreshUI()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        mOpenCvCameraView.setZoom(initZoom)
        mOpenCvCameraView.setExposure(0.0)
        mOpenCvCameraView.setSlowFps()
        outputFull = Mat(height, width, CvType.CV_8UC4)
    }
    override fun onCameraViewStopped() {
        outputFull.release()
    }

    override fun onCameraFrame(inputFrame: CvCameraViewFrame): Mat {
        // NOTE: Consistent way to break app if minify is enabled
        //Runtime.getRuntime().gc();

        val inputColor = inputFrame.rgba()

        inputColor.copyTo(outputFull)

        if (debug) {
            while (mInputDebugSave.size() > INPUT_DEBUG_SAVE_COUNT-1)
                mInputDebugSave.popFirst().release()
            val copy = Mat()
            inputColor.copyTo(copy)
            mInputDebugSave.addLast(copy)
        }
        inputColor.release()

        mDigitizer.parseFrame(inputFrame.gray(), outputFull, debug)
        inputFrame.release()

        /* Have we found a good readout yet? */
        val p = mDigitizer.getParsedText()
        if (p != null) {
            if (debug)
                Imgproc.putText(
                    outputFull, ">$p", Point(0.0, outputFull.size().height - 50),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 10.0, Scalar(255.0, 0.0, 0.0), 30
                )

            // TODO: Make this configurable
            // Parsed text post-processing, add comma
            val newp = p.substring(0, p.length - 1) + "." + p.substring(p.length - 1)

            try {
                readWeight = newp.toDouble()
                initZoom = mOpenCvCameraView.zoom // Remember zoom setting
                runOnUiThread {
                    if (autoSubmit)
                        submitWeight()
                    else
                        mSubmit.isEnabled = true
                    startStop(false)
                }
            } catch (e: NumberFormatException) {
                readWeight = Double.NaN
                runOnUiThread {
                    mSubmit.isEnabled = false
                    startStop(false)
                }
            }
        }
        return outputFull
    }

    private fun saveDebugImages() {
        if (mInputDebugSave.size() == 0 ||
            mInputDebugSave.first.width() == 0 ||
            mInputDebugSave.first.height() == 0) {
            (Toast.makeText(this, "No images to save.", Toast.LENGTH_LONG)).show()
            return
        }

        /* That should not be necessary, but just in case. */
        while (mInputDebugSave.size() > INPUT_DEBUG_SAVE_COUNT)
            mInputDebugSave.popFirst().release()

        val imageSize = mInputDebugSave.first.size()
        val nImages = INPUT_DEBUG_SAVE_COUNT

        val gridWidth = round(sqrt(nImages.toDouble())).toInt()
        val gridHeight = ceil(nImages.toDouble()/gridWidth).toInt()

        val outputSize = Size(imageSize.width * gridWidth, imageSize.height * gridHeight)
        val outputMat = Mat(outputSize, CvType.CV_8UC4)
        var cnt = 0

        while (mInputDebugSave.size() > 0) {
            val input = mInputDebugSave.popFirst()
            if (input.size() != imageSize) {
                (Toast.makeText(this, "Input image size not consistent? " + input.size() + "/" + imageSize, Toast.LENGTH_LONG)).show()
                return
            }

            val x = cnt % gridWidth
            val y = cnt / gridWidth
            val outputMatCrop = outputMat.submat(
                imageSize.height.toInt()*y, imageSize.height.toInt()*(y+1),
                imageSize.width.toInt()*x, imageSize.width.toInt()*(x+1))
            input.copyTo(outputMatCrop)
            outputMatCrop.release()
            input.release()
            cnt++
        }

        val bitmap = Bitmap.createBitmap(outputMat.width(), outputMat.height(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(outputMat, bitmap);
        Thread { /* TODO: This looks very Java-ish, I'm sure we can Kotlinize this. */
            val resolver: ContentResolver = getContentResolver()
            val contentValues = ContentValues()
            val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            val timeStr = ZonedDateTime.now().withNano(0).format(formatter)
            val filename = "SmarterScale-$timeStr.jpg"
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
            contentValues.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/SmarterScale (Debug)"
            )
            val imageUri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            try {
                val fos = resolver.openOutputStream(imageUri!!)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos!!)
                Objects.requireNonNull(fos).close()
                runOnUiThread {
                    (Toast.makeText(
                        this,
                        "Debug image saved as $filename",
                        Toast.LENGTH_LONG
                    )).show()
                }
            } catch (e: IOException) {
                Log.e("PictureDemo", "Exception in photoCallback", e)
            }
        }.start()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
