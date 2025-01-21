package com.drinkcat.smarterscale

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.OnScaleGestureListener
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.runBlocking
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class MainActivity : ComponentActivity(), CvCameraViewListener2, View.OnClickListener,
    OnScaleGestureListener {
    private var mOpenCvCameraView: SmarterCameraView? = null
    private var mCameraViewScaleGestureDetector: ScaleGestureDetector? = null
    private var mWeight: TextView? = null
    private var mStartStop: Button? = null
    private var mSend: Button? = null

    private var digitizer: Digitizer? = null

    private var mSmarterHealthConnect: SmarterHealthConnect? = null

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

        digitizer = Digitizer()

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
        mOpenCvCameraView!!.visibility = SurfaceView.VISIBLE
        mOpenCvCameraView!!.setCvCameraViewListener(this)

        mCameraViewScaleGestureDetector = ScaleGestureDetector(this, this)

        mStartStop = findViewById<View>(R.id.main_activity_start_stop) as Button
        mSend = findViewById<View>(R.id.main_activity_send) as Button
        mSend!!.isEnabled = false
        mWeight = findViewById<View>(R.id.main_activity_weight) as TextView

        mSmarterHealthConnect = SmarterHealthConnect(this)
        runBlocking {
            mSmarterHealthConnect!!.checkPermissions()
        }
    }

    protected fun onCameraPermissionGranted() {
        val cameraViews = cameraViewList ?: return
        for (cameraBridgeViewBase in cameraViews) {
            cameraBridgeViewBase?.setCameraPermissionGranted()
        }
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

    protected val cameraViewList: List<CameraBridgeViewBase?>
        get() = listOf(mOpenCvCameraView)

    /** End of CameraActivity class copies.  */
    public override fun onPause() {
        super.onPause()
        if (mOpenCvCameraView != null) startStop(false)
    }

    public override fun onResume() {
        super.onResume()
        if (mOpenCvCameraView != null) {
            startStop(true)
            mOpenCvCameraView!!.setOnTouchListener { v: View?, event: MotionEvent? ->
                mCameraViewScaleGestureDetector!!.onTouchEvent(
                    event!!
                )
            }
        }
        if (mStartStop != null) mStartStop!!.setOnClickListener(this)
        if (mSend != null) mSend!!.setOnClickListener(this)
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (mOpenCvCameraView != null) startStop(false)
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
    }

    override fun onCameraViewStopped() {
    }

    private var started = false
    private var readWeight = Double.NaN
    fun startStop(start: Boolean) {
        if (start) {
            init = false
            mSend!!.isEnabled = false
            mStartStop!!.text = "Stop"
            readWeight = Double.NaN
            mWeight!!.text = "??.?"
            digitizer!!.reset()
            mOpenCvCameraView!!.enableView()
        } else {
            mStartStop!!.text = "Start"
            mOpenCvCameraView!!.disableView()
        }
        started = start
    }

    private var beginSpan = 0f
    private var beginZoom = 1.0

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        beginSpan = detector.currentSpan
        beginZoom = mOpenCvCameraView!!.zoom
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
        mOpenCvCameraView!!.zoom = newZoom
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {}

    override fun onClick(v: View) {
        if (v === mStartStop) {
            startStop(!started)
        } else if (v === mSend) {
            mSmarterHealthConnect!!.writeWeightInputBlocking(readWeight)
        }
    }

    var init: Boolean = false

    override fun onCameraFrame(inputFrame: CvCameraViewFrame): Mat {
        if (!init) {
            // TODO: Remember zoom level. Set zoom to maximum for now.
            mOpenCvCameraView!!.zoom = 1.0
            mOpenCvCameraView!!.setExposure(0.0)
            mOpenCvCameraView!!.setSlowFps()
            init = true
            return inputFrame.rgba()
        }

        val inputColor = inputFrame.rgba()
        val inputSize = inputColor.size()

        /* output color frame that includes drawn shapes. */
        val outputFull = Mat()
        inputColor.copyTo(outputFull)
        inputColor.release()

        digitizer!!.parseFrame(inputFrame.gray(), outputFull)

        /* Have we found a good readout yet? */
        val p = digitizer!!.parsedText
        if (p != null) {
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
                    mWeight!!.text = readWeight.toString()
                    mSend!!.isEnabled = true
                    startStop(false)
                }
            } catch (e: NumberFormatException) {
                readWeight = Double.NaN
                this.runOnUiThread {
                    mWeight!!.text = "BAD"
                    mSend!!.isEnabled = false
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
