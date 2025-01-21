package com.drinkcat.smarterscale;

import android.os.Bundle;
import android.util.Log;
import android.view.ScaleGestureDetector;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.PermissionController;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class MainActivity extends CameraActivity implements CvCameraViewListener2, View.OnClickListener, ScaleGestureDetector.OnScaleGestureListener {
    private static final String TAG = "MainActivity";

    private SmarterCameraView mOpenCvCameraView;
    private ScaleGestureDetector mCameraViewScaleGestureDetector;
    private TextView mWeight;
    private Button mStartStop;
    private Button mSend;


    private Digitizer digitizer;

    private HealthConnectClient mHealthConnectClient;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show();
            return;
        }

        digitizer = new Digitizer();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        mOpenCvCameraView = (SmarterCameraView) findViewById(R.id.main_activity_smarter_camera_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        mCameraViewScaleGestureDetector = new ScaleGestureDetector(this, this);

        mStartStop = (Button) findViewById(R.id.main_activity_start_stop);
        mSend = (Button) findViewById(R.id.main_activity_send);
        mSend.setEnabled(false);
        mWeight = (TextView) findViewById(R.id.main_activity_weight);

        checkHealthConnectPermissions();
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            startStop(false);
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (mOpenCvCameraView != null) {
            startStop(true);
            mOpenCvCameraView.setOnTouchListener(
                    (v, event) -> mCameraViewScaleGestureDetector.onTouchEvent(event)
            );
        }
        if (mStartStop != null) {
            mStartStop.setOnClickListener(this);
        }
    }
    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            startStop(false);
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
    }

    public boolean started = false;
    public void startStop(boolean start) {
        if (start) {
            init = false;
            mSend.setEnabled(false);
            mStartStop.setText("Stop");
            mWeight.setText("??.?");
            digitizer.reset();
            mOpenCvCameraView.enableView();
        } else {
            mStartStop.setText("Start");
            mOpenCvCameraView.disableView();
        }
        started = start;
    }

    private float beginSpan;
    private double beginZoom = 1.0;

    @Override
    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
        beginSpan = detector.getCurrentSpan();
        beginZoom = mOpenCvCameraView.getZoom();
        return true;
    }

    @Override
    public boolean onScale(@NonNull ScaleGestureDetector detector) {
        if (!Double.isFinite(beginZoom))
            return true;
        float span = detector.getCurrentSpan();
        double newZoom = beginZoom + (span - beginSpan) / beginSpan;
        Log.d(TAG, "onScale zoom " + beginSpan + "/" + span + " => " + newZoom + "(" + beginZoom);
        /* TODO: Manually crop input more if newZoom > 1.0. */
        mOpenCvCameraView.setZoom(newZoom);
        return true;
    }

    @Override
    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {}

    @Override
    public void onClick(View v) {
        if (v == mStartStop)
            startStop(!started);
        else if (v == mSend)
            ; // TODO: Send data!
    }

    boolean init = false;

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        if (!init) {
            // TODO: Remember zoom level. Set zoom to maximum for now.
            mOpenCvCameraView.setZoom(1.0);
            mOpenCvCameraView.setExposure(0.0);
            mOpenCvCameraView.setSlowFps();
            init = true;
            return inputFrame.rgba();
        }

        Mat inputColor = inputFrame.rgba();
        Size inputSize = inputColor.size();

        /* output color frame that includes drawn shapes. */
        Mat outputFull = new Mat();
        inputColor.copyTo(outputFull);
        inputColor.release();

        digitizer.parseFrame(inputFrame.gray(), outputFull);

        /* Have we found a good readout yet? */
        String p = digitizer.getParsedText();
        if (p != null) {
            Imgproc.putText(outputFull, ">" + p, new Point(0, outputFull.size().height-50),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 10, new Scalar(255, 0, 0), 30);

            // TODO: Make this configurable
            // Parsed text post-processing, add comma
            String newp = p.substring(0, p.length()-1) + "." + p.substring(p.length()-1);

            this.runOnUiThread(() -> {
                mWeight.setText(newp);
                mSend.setEnabled(true);
                startStop(false);
            });
        }
        return outputFull;
    }

    private void checkHealthConnectPermissions() {
        int availabilityStatus = HealthConnectClient.getSdkStatus(this, null);
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            Log.e(TAG, "Health connect not available!");
            Toast.makeText(this, "Health connect not available!", Toast.LENGTH_LONG).show();
            return;
        }
        mHealthConnectClient = HealthConnectClient.getOrCreate(this);

        /*
        val PERMISSIONS =
                setOf(
                        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class)
)*/

        ActivityResultContract<Set<String>, Set<String>> requestPermissionActivityContract =
                PermissionController.createRequestPermissionResultContract();

        Set<String> permissions;
        mHealthConnectClient.getPermissionController().getGrantedPermissions();
    }
}
