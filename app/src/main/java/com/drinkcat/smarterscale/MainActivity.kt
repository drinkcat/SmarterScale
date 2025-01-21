package com.drinkcat.smarterscale

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MenuInflater
import android.view.ScaleGestureDetector
import android.view.SurfaceView
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

    private var started = false
    private var readWeight = Double.NaN
    private var debug = true
        set(value) {
            field = value
            mStartStop.visibility =
                if (debug || !started) View.VISIBLE else View.INVISIBLE
        }
    private var autoSubmit = false
        set(value) {
            field = value
            mSubmit.visibility =
                if (autoSubmit) View.INVISIBLE else View.VISIBLE
        }
    private var showHelp = true
        set(value) {
            field = value
            mHelp.visibility =
                if (showHelp && !readWeight.isFinite()) View.VISIBLE else View.INVISIBLE
            mWeight.visibility =
                if (showHelp) View.INVISIBLE else View.VISIBLE
        }

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "called onCreate")
        super.onCreate(savedInstanceState)

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show()
            return
        }

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

        lifecycle.coroutineScope.launch {
            mSmarterHealthConnect.checkPermissions()
        }

        mOpenCvCameraView =
            findViewById<View>(R.id.main_activity_smarter_camera_view) as SmarterCameraView
        mOpenCvCameraView.visibility = SurfaceView.VISIBLE
        mOpenCvCameraView.setCvCameraViewListener(this)

        mHelp = findViewById<View>(R.id.main_activity_help) as TextView
        mWeight = findViewById<View>(R.id.main_activity_weight) as TextView

        mStartStop = findViewById<View>(R.id.main_activity_start_stop) as Button
        mSubmit = findViewById<View>(R.id.main_activity_submit) as Button
        mSubmit.isEnabled = false

        debug = true
        showHelp = true

        val scalegesturedetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var beginSpan = 0f
                private var beginZoom = 1.0

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    beginSpan = detector.currentSpan
                    beginZoom = mOpenCvCameraView.zoom
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!java.lang.Double.isFinite(beginZoom)) return true
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
            scalegesturedetector.onTouchEvent(event)
        }

        mStartStop.setOnClickListener {
            startStop(!started)
        }
        mSubmit.setOnClickListener {
            submitWeight()
        }

        val menulistener = PopupMenu.OnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_debug -> { debug = !item.isChecked(); true }
                R.id.menu_auto -> { autoSubmit = !item.isChecked(); true }
                R.id.menu_showhelp -> { showHelp = !item.isChecked(); true }
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

    private fun submitWeight() {
        mSubmit.isEnabled = false;
        lifecycle.coroutineScope.launch {
            mSmarterHealthConnect.writeWeightInput(readWeight)
        }
    }

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

    /** End of CameraActivity class copies.  */
    public override fun onPause() {
        super.onPause()
        startStop(false)
    }

    public override fun onResume() {
        super.onResume()
        startStop(true)
    }

    public override fun onDestroy() {
        super.onDestroy()
        startStop(false)
    }

    override fun onCameraViewStarted(width: Int, height: Int) {}

    override fun onCameraViewStopped() {}

    private fun startStop(start: Boolean) {
        if (start) {
            init = false
            mSubmit.isEnabled = false
            mStartStop.text = "Stop"
            mStartStop.visibility =
                if (debug) View.VISIBLE else View.INVISIBLE
            readWeight = Double.NaN
            mWeight.text = "??.?"
            mHelp.visibility = if (showHelp) View.VISIBLE else View.INVISIBLE
            mWeight.visibility = if (showHelp) View.INVISIBLE else View.VISIBLE
            mDigitizer.reset()
            mOpenCvCameraView.enableView()
        } else {
            if (readWeight.isFinite())
                mStartStop.text = "Restart"
            else
                mStartStop.text = "Start"
            mStartStop.visibility = View.VISIBLE
            mOpenCvCameraView.disableView()
        }
        started = start
    }

    var init: Boolean = false

    override fun onCameraFrame(inputFrame: CvCameraViewFrame): Mat {
        if (!init) {
            // TODO: Remember zoom level. Set zoom to maximum for now.
            mOpenCvCameraView.zoom = 1.0
            mOpenCvCameraView.setExposure(0.0)
            mOpenCvCameraView.setSlowFps()
            init = true
            return inputFrame.rgba()
        }

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
                this.runOnUiThread {
                    mHelp.visibility = View.INVISIBLE
                    mWeight.text = readWeight.toString()
                    mWeight.visibility = View.VISIBLE
                    if (autoSubmit)
                        submitWeight()
                    else
                        mSubmit.isEnabled = true
                    startStop(false)
                }
            } catch (e: NumberFormatException) {
                readWeight = Double.NaN
                this.runOnUiThread {
                    mHelp.visibility = View.INVISIBLE
                    mWeight.text = "BAD"
                    mWeight.visibility = View.VISIBLE
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
