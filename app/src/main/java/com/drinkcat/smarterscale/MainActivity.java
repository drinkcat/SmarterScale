package com.drinkcat.smarterscale;

import android.hardware.Camera;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class MainActivity extends CameraActivity implements CvCameraViewListener2, View.OnTouchListener {
    private static final String TAG = "MainActivity";

    private SmarterCameraView mOpenCvCameraView;

    private Digitizer digitizer;

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

        mOpenCvCameraView = (SmarterCameraView) findViewById(R.id.tutorial1_activity_java_surface_view);

        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);

        mOpenCvCameraView.setCvCameraViewListener(this);
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
            mOpenCvCameraView.setOnTouchListener(this);
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
            digitizer.reset();
            mOpenCvCameraView.enableView();
        } else {
            mOpenCvCameraView.disableView();
        }
        started = start;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        startStop(!started);
        return true;
    }

    boolean init = false;

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        // FIXME: we like zoom, zoomies!
        // TODO: Remember zoom level, manually crop more if needed.
        if (!init) {
            List<Integer> zooms = mOpenCvCameraView.getZoomRatios();
            mOpenCvCameraView.setZoom(zooms.size() - 1);
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
            this.runOnUiThread(() -> {
                (Toast.makeText(this, "Read out <" + p + ">!", Toast.LENGTH_LONG)).show();
                startStop(false);
            });
        }
        return outputFull;
    }
}

class TaggedRect extends Rect {
    public boolean visited = false;

    TaggedRect(Rect r) { this(r, false); }
    TaggedRect(Rect r, boolean visited) {
        super(r.x, r.y, r.width, r.height); this.visited = visited;
    }
}

class RectUtils {
    public static double sumRect(Mat int_thresh, Rect rect) {
        return int_thresh.get(rect.y+rect.height, rect.x+rect.width)[0]
                - int_thresh.get(rect.y+rect.height, rect.x)[0]
                - int_thresh.get(rect.y, rect.x+rect.width)[0]
                + int_thresh.get(rect.y, rect.x)[0];
    }

    public static  Rect overlapRaw(Rect a, Rect b) {
        int x = Math.max(a.x, b.x);
        int y = Math.max(a.y, b.y);
        int width = Math.min(a.x+a.width, b.x+b.width) - x;
        int height = Math.min(a.y+a.height, b.y+b.height) - y;

        return new Rect(x, y, width, height);
    }

    public static boolean overlapCheck(Rect a, Rect b) {
        Rect o = overlapRaw(a, b);
        return (o.width >= 0 && o.height >= 0);
    }

    public static Rect union(Rect a, Rect b) {
        if (a == null) return b;
        if (b == null) return a;

        int x = Math.min(a.x, b.x);
        int y = Math.min(a.y, b.y);
        int width = Math.max(a.x+a.width, b.x+b.width) - x;
        int height = Math.max(a.y+a.height, b.y+b.height) - y;

        return new Rect(x, y, width, height);
    }
}