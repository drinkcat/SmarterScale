package com.drinkcat.smarterscale

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.iterator
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.launch
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class MainActivity : ComponentActivity(), CvCameraViewListener2 {
    /* UI elements */
    private lateinit var mOpenCvCameraView: SmarterCameraView
    private lateinit var mHelp: TextView
    private lateinit var mWeight: TextView
    private lateinit var mStartStop: Button
    private lateinit var mSubmit: Button

    private var mDigitizer = Digitizer();
    private var mSmarterHealthConnect = SmarterHealthConnect(this)

    /* State */
    private var started = false
    private var readWeight: Double? = null
    private var debug = true
    private var autoSubmit = false
    private var showHelp = true

    /* Initialization functions */
    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "called onCreate")
        super.onCreate(savedInstanceState)

        if (!openCVInit()) return
        mSmarterHealthConnect.init()

        setupBasicLayout()
        setupEventListeners()
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

        mHelp = findViewById<View>(R.id.main_activity_help) as TextView
        mWeight = findViewById<View>(R.id.main_activity_weight) as TextView

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

        val menulistener = PopupMenu.OnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_debug -> { debug = !item.isChecked(); refreshUI(); true }
                R.id.menu_auto -> { autoSubmit = !item.isChecked(); refreshUI(); true }
                R.id.menu_showhelp -> { showHelp = !item.isChecked(); refreshUI(); true }
                R.id.menu_privacy -> {
                    startActivity(Intent(this, PermissionsRationaleActivity::class.java));
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
                    R.id.menu_debug -> item.setChecked(debug)
                    R.id.menu_auto -> item.setChecked(autoSubmit)
                    R.id.menu_showhelp -> item.setChecked(showHelp)
                }
            }
            popup.setOnMenuItemClickListener(menulistener)
            popup.show()
        }
    }
    /* end of Init functions */

    private fun onCameraPermissionGranted() {
        mOpenCvCameraView.setCameraPermissionGranted()
    }

    override fun onStart() {
        super.onStart()
        var havePermission = true
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
            havePermission = false
        }
        if (havePermission) {
            onCameraPermissionGranted()
        }
    }

    @Deprecated("")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onCameraPermissionGranted()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    public override fun onPause() {
        Log.d(TAG, "onPause");
        super.onPause()
        startStop(false)
    }

    public override fun onResume() {
        Log.d(TAG, "onResume");
        super.onResume()
        if (readWeight == null)
            startStop(true)
        else
            refreshUI()
    }

    public override fun onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy()
        startStop(false)
    }

    private fun submitWeight() {
        if (readWeight == null || !readWeight!!.isFinite())
            return;
        mSubmit.isEnabled = false;
        lifecycle.coroutineScope.launch {
            mSmarterHealthConnect.writeWeightInput(readWeight!!)
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

        /* Visibility */
        fun boolToVisible(b: Boolean): Int = if (b) View.VISIBLE else View.INVISIBLE

        val weightVisible = readWeight != null || !showHelp
        mWeight.visibility = boolToVisible(weightVisible)
        mHelp.visibility = boolToVisible(!weightVisible)
        mStartStop.visibility = boolToVisible(debug || !started)
        mSubmit.visibility = boolToVisible(!autoSubmit)
        if (started)
            mOpenCvCameraView.enableView()
        else
            mOpenCvCameraView.disableView()
    }

    private fun startStop(start: Boolean) {
        started = start
        if (start) {
            mSubmit.isEnabled = false
            readWeight = null
            mDigitizer.reset()
        }
        refreshUI()
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        // TODO: Remember zoom level. Set zoom to maximum for now.
        mOpenCvCameraView.setZoom(1.0)
        mOpenCvCameraView.setExposure(0.0)
        mOpenCvCameraView.setSlowFps()
    }
    override fun onCameraViewStopped() {}

    override fun onCameraFrame(inputFrame: CvCameraViewFrame): Mat {
        val inputColor = inputFrame.rgba()

        /* output color frame that includes drawn shapes. */
        val outputFull = Mat()
        inputColor.copyTo(outputFull)
        inputColor.release()

        mDigitizer.parseFrame(inputFrame.gray(), outputFull, debug)

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

    companion object {
        private const val TAG = "MainActivity"

        /** Copied functions from openCV's CameraActivity class, as we want to extend ComponentActivity.  */
        private const val CAMERA_PERMISSION_REQUEST_CODE = 200
    }
}
