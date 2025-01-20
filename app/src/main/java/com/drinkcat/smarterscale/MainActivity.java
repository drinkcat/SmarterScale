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
            parsedText.clear();
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

    public double sumRect(Mat int_thresh, Rect rect) {
        return int_thresh.get(rect.y+rect.height, rect.x+rect.width)[0]
                - int_thresh.get(rect.y+rect.height, rect.x)[0]
                - int_thresh.get(rect.y, rect.x+rect.width)[0]
                + int_thresh.get(rect.y, rect.x)[0];
    }

    private class TaggedRect extends Rect {
        public boolean visited = false;

        TaggedRect(Rect r) { this(r, false); }
        TaggedRect(Rect r, boolean visited) {
            super(r.x, r.y, r.width, r.height); this.visited = visited;
        }
    }

    private Rect overlapRaw(Rect a, Rect b) {
        int x = Math.max(a.x, b.x);
        int y = Math.max(a.y, b.y);
        int width = Math.min(a.x+a.width, b.x+b.width) - x;
        int height = Math.min(a.y+a.height, b.y+b.height) - y;

        return new Rect(x, y, width, height);
    }

    private boolean overlapCheck(Rect a, Rect b) {
        Rect o = overlapRaw(a, b);
        return (o.width >= 0 && o.height >= 0);
    }

    private Rect union(Rect a, Rect b) {
        if (a == null) return b;
        if (b == null) return a;

        int x = Math.min(a.x, b.x);
        int y = Math.min(a.y, b.y);
        int width = Math.max(a.x+a.width, b.x+b.width) - x;
        int height = Math.max(a.y+a.height, b.y+b.height) - y;

        return new Rect(x, y, width, height);
    }

    private int sigArrayToSig(int[] sigarray) {
        if (sigarray == null)
            return -1;
        int sig = 0;
        for (int i: sigarray) {
            sig <<= 1;
            sig |= i;
        }
        return sig;
    }

    /* We want to find digits that take 20-35% of the image height. */
    final double MIN_ASSUMED_HEIGHT = 0.20;
    final double MAX_ASSUMED_HEIGHT = 0.35;

    /* Vertical segments tend to be about half the size. */
    /* TODO: Configurable. */
    final double SEGMENT_VERT_HORIZ_RATIO = 0.5;
    final double MIN_VERT_SEGMENT_RATIO = 1.67;
    final double MAX_VERT_SEGMENT_RATIO = 2.67;
    final double MIN_HORIZ_SEGMENT_RATIO = 3.0;
    final double MAX_HORIZ_SEGMENT_RATIO = 4.5;
    /* /TODO: Configurable */

    /* 0.9: Segments can be shorter than half the height */
    final double MIN_VERT_SEGMENT_HEIGHT = MIN_ASSUMED_HEIGHT * 0.5 * 0.9;
    final double MAX_VERT_SEGMENT_HEIGHT = MAX_ASSUMED_HEIGHT * 0.5;

    final double MIN_HORIZ_SEGMENT_HEIGHT = MIN_VERT_SEGMENT_HEIGHT * SEGMENT_VERT_HORIZ_RATIO;
    final double MAX_HORIZ_SEGMENT_HEIGHT = MAX_ASSUMED_HEIGHT * SEGMENT_VERT_HORIZ_RATIO / 0.8;

    final double MIN_FILL_RATIO = 0.7;

    LinkedList<String> parsedText = new LinkedList<>();
    private void findDigits(Mat output, Mat int_thresh, List<MatOfPoint> cnts) {
        LinkedList<TaggedRect> rectIgnore = new LinkedList<TaggedRect>();
        LinkedList<TaggedRect> rectGood = new LinkedList<TaggedRect>(); // Vertical and horizontal segments

        for (MatOfPoint c: cnts) {
            Rect rect = Imgproc.boundingRect(c);

            if (rect.width < 10 || rect.height < 10) {
                //rectIgnore.add(rect);
                //Log.d(TAG, "ignore too small: " + rect);
                continue;
            }

            double h = rect.height;
            double w = rect.width;
            double ratio = (w > h) ? w / h : h / w;

            if ((w > h && (ratio < MIN_VERT_SEGMENT_RATIO || ratio > MAX_VERT_SEGMENT_RATIO)) ||
                    (w <= h && (ratio < MIN_HORIZ_SEGMENT_RATIO || ratio > MAX_HORIZ_SEGMENT_RATIO))) {
                rectIgnore.add(new TaggedRect(rect));
                //Log.d(TAG, "ignore by ratio: " + rect + " / " + ratio);
                continue;
            }

            double area = sumRect(int_thresh, rect);
            double rect_area = rect.area();
            double fillratio = area / rect_area / 255;

            if (fillratio < MIN_FILL_RATIO) { // # Unlikely a segment/dot
                rectIgnore.add(new TaggedRect(rect));
                //Log.d(TAG, "ignore by fill ratio: " + rect + " // " + fillratio);
                continue;
            }

            /* Now expand a bit the rectangle to facilitate matching */
            final double EXPAND_RATIO = 1.0;
            if (w > h) {
                rect.x -= rect.height * EXPAND_RATIO/2;
                rect.width += rect.height * EXPAND_RATIO;
            } else {
                rect.y -= rect.width * EXPAND_RATIO/2;
                rect.height += rect.width * EXPAND_RATIO;
            }

            rectGood.add(new TaggedRect(rect));

            Log.d(TAG, "good: " + rect + " / " + ratio + " // " + fillratio);
        }

        for (Rect rect: rectIgnore)
            Imgproc.rectangle(output, rect, new Scalar(127,0,0), 1);

        for (Rect rect: rectGood)
            Imgproc.rectangle(output, rect, new Scalar(0,0,255), 3);

        LinkedList<List<Rect>> groups = new LinkedList<List<Rect>>();

        // Extract digits, bruteforce (can most likely be optimized)
        for (TaggedRect r1: rectGood) {
            if (r1.visited) continue;
            r1.visited = true;
            LinkedList<Rect> group = new LinkedList<>();
            group.add(r1);
            boolean added;
            do {
                added = false;
                for (TaggedRect r2: rectGood) {
                    if (r2.visited) continue;
                    for (Rect r3: group) {
                        if (!overlapCheck(r2, r3)) continue;
                        r2.visited = true;
                        added = true;
                        break;
                    }
                    if (r2.visited) // Add outside the loop to avoid concurrent modification
                        group.add(r2);
                }
            } while (added);
            groups.add(group);
        }

        /* TODO: This could be initialized only once */
        HashMap<Integer,String> DIGITMAP = new HashMap<Integer, String>();
        DIGITMAP.put(sigArrayToSig(new int[]{1, 0, 1, 1, 1, 1, 1}), "0");
        DIGITMAP.put(sigArrayToSig(new int[]{0, 0, 0, 1, 0, 1, 0}), "1");
        DIGITMAP.put(sigArrayToSig(new int[]{1, 1, 1, 0, 1, 1, 0}), "2");
        DIGITMAP.put(sigArrayToSig(new int[]{1, 1, 1, 0, 1, 0, 1}), "3");
        DIGITMAP.put(sigArrayToSig(new int[]{0, 1, 0, 1, 1, 0, 1}), "4");
        DIGITMAP.put(sigArrayToSig(new int[]{1, 1, 1, 1, 0, 0, 1}), "5");
        DIGITMAP.put(sigArrayToSig(new int[]{1, 1, 1, 1, 0, 1, 1}), "6");
        DIGITMAP.put(sigArrayToSig(new int[]{1, 0, 0, 0, 1, 0, 1}), "7");
        DIGITMAP.put(sigArrayToSig(new int[]{1, 1, 1, 1, 1, 1, 1}), "8");
        DIGITMAP.put(sigArrayToSig(new int[]{1, 1, 1, 1, 1, 0, 1}), "9");

        HashMap<Integer,String> digits = new HashMap<Integer, String>();
        for (List<Rect> group: groups) {
            Rect u = null;
            for (Rect r : group)
                u = union(u, r);
            Imgproc.rectangle(output, u, new Scalar(255, 0, 255), 5);
            int[] sigarray = new int[7];
            for (Rect r : group) {
                int sx = (int)Math.round(1.0 * (r.x - u.x) / u.width);
                int sy = (int)Math.round(2.0 * (r.y - u.y) / u.height);
                int idx;
                if (r.width > r.height)
                    idx = sy;
                else
                    idx = 3 + 2 * sy + sx;
                if (idx < 0 || idx >= sigarray.length) {
                    sigarray = null;
                    break;
                }
                sigarray[idx] = 1;
            }
            String digit = DIGITMAP.get(sigArrayToSig(sigarray));
            Log.d(TAG, "found digit: " + digit + " from " + Arrays.toString(sigarray));

            if (digit == null)
                continue;

            digits.put(u.x, digit);
        }

        StringBuilder sb = new StringBuilder();
        ArrayList<Integer> keys = new ArrayList<Integer>(digits.keySet());
        Collections.sort(keys);
        for (int ux: keys)
            sb.append(digits.get(ux));
        String s = sb.toString();
        Log.d(TAG, "Parsed: " + s);

        Imgproc.putText(output, s, new Point(0, output.size().height),
                Imgproc.FONT_HERSHEY_SIMPLEX, 5, new Scalar(128, 128, 255), 5);

        // TODO: Using a circular buffer would be better...
        parsedText.addFirst(s);
        while (parsedText.size() > 30)
            parsedText.removeLast();
    }

    boolean init = false;

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        // FIXME: we like zoom, zoomies!
        // TODO: Remember zoom level, manually crop more if needed.
        if (!init) {
            List<Integer> zooms = mOpenCvCameraView.getZoomRatios();
            mOpenCvCameraView.setZoom(zooms.size() - 1);
            mOpenCvCameraView.setExposure(0.0);
            init = true;
            return inputFrame.rgba();
        }

        Mat inputColor = inputFrame.rgba();
        Size inputSize = inputColor.size();

        final double ROI_SIZE = 0.66;
        int roi_size = (int)(Math.min(inputSize.width, inputSize.height) * ROI_SIZE);
        Rect crop = new Rect(
                (int)(inputSize.width - roi_size)/2, (int)(inputSize.height - roi_size)/ 2,
                roi_size, roi_size);

        Mat inputGray = inputFrame.gray().submat(crop);
        Mat outputFull = new Mat(); /* output color frame that includes drawn shapes. */
        inputColor.copyTo(outputFull);
        inputColor.release();
        Mat output = outputFull.submat(crop);

        Mat thresh = new Mat();
        final double MEDIAN_BLUR_SIZE = 0.002; /* Meant to be 5px for 1000px input */
        final double ADAPTIVE_THRESHOLD_SIZE = 0.03; /* Meant to be ~50px for 1000px input */
        int medianBlurSize = (int)(MEDIAN_BLUR_SIZE * inputSize.width);
        if ((medianBlurSize % 2) == 0)
            medianBlurSize += 1;
        Imgproc.medianBlur(inputGray, thresh, medianBlurSize);
        inputGray.release();
        int blockSize = (int)(ADAPTIVE_THRESHOLD_SIZE * inputSize.width);
        if ((blockSize % 2) == 0)
            blockSize += 1;
        Imgproc.adaptiveThreshold(thresh, thresh,255,Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY, blockSize,1);

        int kernelSize = 3;
        Mat element = new Mat(2 * kernelSize + 1, 2 * kernelSize + 1, CvType.CV_8U, new Scalar(0));
        for (int i = 0; i < element.rows(); i++) {
            element.put(i, i, 1.0);
            element.put(element.cols()-i-1, i, 1.0);
        }
        Imgproc.morphologyEx(thresh, thresh, Imgproc.MORPH_OPEN, element, new Point(-1, -1), 2);

        List<MatOfPoint> cnts = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, cnts, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        Mat int_thresh = new Mat();
        Imgproc.integral(thresh, int_thresh);
        thresh.release();

        // FIXME: This is ugly, I'm computing contourArea over and over again.
        cnts.sort(Comparator.comparingDouble((Mat c) -> Imgproc.contourArea(c)).reversed());

        Imgproc.drawContours(output, cnts, -1, new Scalar(0,128,0), 2);

        findDigits(output, int_thresh, cnts);
        int_thresh.release();

        /* Guiding rectangle for the user. */
        int min_height = (int)(inputSize.height*MIN_ASSUMED_HEIGHT);
        int max_height = (int)(inputSize.height*MAX_ASSUMED_HEIGHT);
        Rect rect = new Rect(crop.x, (int)(inputSize.height-min_height)/2, crop.width, min_height);
        Imgproc.rectangle(outputFull, rect, new Scalar(255,0,0), (int)(0.005*inputSize.width));
        rect = new Rect(crop.x, (int)(inputSize.height-max_height)/2, crop.width, max_height);
        Imgproc.rectangle(outputFull, rect, new Scalar(255,0,0), (int)(0.005*inputSize.width));

        /* Have we found a good readout yet? */
        HashMap<String, Integer> pcount = new HashMap<String, Integer>();
        for (String p: parsedText) {
            if (p.length() == 0)
                continue;
            int cnt = pcount.getOrDefault(p, 0)+1;
            if (cnt > 10) {
                Imgproc.putText(outputFull, ">" + p, new Point(0, outputFull.size().height-50),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 10, new Scalar(255, 0, 0), 30);
                this.runOnUiThread(() -> {
                    (Toast.makeText(this, "Read out <" + p + ">!", Toast.LENGTH_LONG)).show();
                    startStop(false);
                });
                break;
            }
            pcount.put(p, cnt);
        }

        return outputFull;
    }
}