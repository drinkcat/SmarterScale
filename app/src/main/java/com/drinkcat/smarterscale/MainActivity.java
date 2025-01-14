package com.drinkcat.smarterscale;

import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.SurfaceView;
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
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

public class MainActivity extends CameraActivity implements CvCameraViewListener2 {
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
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.enableView();
    }
    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
    }

    public double sumRect(Mat int_thresh, Rect rect) {
        return int_thresh.get(rect.y+rect.height, rect.x+rect.width)[0]
                - int_thresh.get(rect.y+rect.height, rect.x)[0]
                - int_thresh.get(rect.y, rect.x+rect.width)[0]
                + int_thresh.get(rect.y, rect.x)[0];
    }

    private Mat findDigits(Mat input) {
        /* TODO: original version needed 1000x1000 image... */
        // image = imutils.resize(image, height=1000)

        Mat gray = new Mat();
        Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY);
        // # TODO: Compute OTSU within display only
        Mat thresh = new Mat();
        Imgproc.threshold(gray, thresh, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
        Mat int_thresh = new Mat();
        Imgproc.integral(thresh, int_thresh);

        List<MatOfPoint> cnts = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();

        /* # TODO: Consider adding erosion/dilation dynamically
        kernel = numpy.ones((3, 3), numpy.uint8)
        thresh = cv2.erode(thresh, kernel, iterations=5)
        thresh = cv2.dilate(thresh, kernel, iterations=5)
        */

        Imgproc.findContours(thresh, cnts, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        //Imgproc.drawContours(input, cnts, -1, new Scalar(0,255,0), 3);

        LinkedList<Rect> rectIgnore = new LinkedList<Rect>();
        LinkedList<Rect> rectGood = new LinkedList<Rect>(); // Vertical and horizontal segments
        LinkedList<Rect> rectDot = new LinkedList<Rect>(); // Dots

        for (MatOfPoint c: cnts) {
            Rect rect = Imgproc.boundingRect(c);

            if (rect.width < 10 || rect.height < 10) {
                //rectIgnore.add(rect);
                Log.d(TAG, "ignore too small: " + rect);
                continue;
            }

            if (rect.x < 10 || rect.y < 10 ||
                    rect.x + rect.width > thresh.size().width - 10 ||
                    rect.y + rect.height > thresh.size().height - 10) {
                //rectIgnore.add(rect);
                Log.d(TAG, "ignore on the edge: " + rect + " // " + thresh.size());
                continue;
            }

            double h = rect.height;
            double w = rect.width;
            double ratio = (w > h) ? w/h : h/w;

            if (ratio > 1.1 && ratio < 2.0) {
                rectIgnore.add(rect);
                Log.d(TAG, "ignore by ratio: " + rect + " / " + ratio);
                continue;
            }

            double area = sumRect(int_thresh, rect);
            double rect_area = rect.area();
            double fillratio = area / rect_area / 255;

            if (fillratio < 0.50) { // # Unlikely a segment?
                rectIgnore.add(rect);
                Log.d(TAG, "ignore by fill ratio: " + rect + " // " + fillratio);
                continue;
            }

            if (ratio > 2.0) {
                rectGood.add(rect); // TODO: ADD False TAG
            } else {
                rectDot.add(rect); // TODO: ADD False TAG
            }
            Log.d(TAG, "good: " + rect + " / " + ratio + " // " + fillratio);
        }

        for (Rect rect: rectIgnore)
            Imgproc.rectangle(input, rect, new Scalar(255,0,0), 1);

        for (Rect rect: rectGood)
            Imgproc.rectangle(input, rect, new Scalar(0,0,255), 1);

        for (Rect rect: rectDot)
            Imgproc.rectangle(input, rect, new Scalar(0,255,255), 1);

        return input;
    }

        @Override
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        // FIXME: we like zoom, zoomies!
        List<Integer> zooms = mOpenCvCameraView.getZoomRatios();
        mOpenCvCameraView.setZoom(zooms.size() - 1);

        Mat inputColor = inputFrame.rgba();
        Size inputSize = inputColor.size();
        Mat outputColor = new Mat();

        Mat inputGray = new Mat();
        // TODO: okay to assume square input?
        // TODO: Pick a good dimension at input
        double origScale = inputSize.height/1000.0;
        // TODO: Silly to resize twice
        Imgproc.resize(inputFrame.gray(), inputGray, new Size(), 1.0/origScale, 1.0/origScale, Imgproc.INTER_AREA);
        Imgproc.resize(inputColor, outputColor, new Size(), 1.0/origScale, 1.0/origScale, Imgproc.INTER_AREA);
        Mat thresh = new Mat();
        Imgproc.threshold(inputGray, thresh, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        List<MatOfPoint> cnts = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, cnts, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        // FIXME: This is ugly, I'm computing contourArea over and over again.
        cnts.sort(Comparator.comparingDouble((Mat c) -> Imgproc.contourArea(c)).reversed());
        //cnts = sorted(cnts, key=cv2.contourArea, reverse=True)

        boolean found = false;
        /* FIXME: Ugly pass-through */
        MatOfPoint2f c2f = null;
        Mat box = null;
        double angle = 0.0;
        for (MatOfPoint c: cnts) {
            c2f = new MatOfPoint2f(c.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            RotatedRect rect = Imgproc.minAreaRect(c2f);
            box = new Mat();
            Imgproc.boxPoints(rect, box);
            // TODO: box = numpy.int32(box)
            double h = rect.size.height;
            double w = rect.size.width;
            double rawangle = rect.angle;
            if (h < 100 && w < 100)
                continue;

            double ratio = (w > h) ? w/h : h/w;
            /* Angle will be between 0 and 90 */
            if (rawangle > 45.0)
                angle = 90 - rawangle;
            else
                angle = rawangle;
            // TODO: Find the best one instead?
            Log.d(TAG, "h/w:" + h + "x" + w + "; angle=" + rawangle + "=>" + angle);
            if (h > 100 && w > 100 && ratio < 1.5 && Math.abs(angle) < 30 ) {
                found = true;
                break;
            }
        }

        Imgproc.drawContours(outputColor, cnts, -1, new Scalar(0,255,0), 1);
        if (!found) {
            Mat output = new Mat();
            // TODO: Silly to resize back
            Imgproc.resize(outputColor, output, inputSize, 0, 0, Imgproc.INTER_AREA);
            return output;
        }

        // FIXME: c, angle, and box are used below, be careful this is ugly
        Rect rect = Imgproc.boundingRect(c2f);
        Imgproc.rectangle(outputColor, rect, new Scalar(255,0,0), 5);
        rect.x *= origScale; rect.y *= origScale;
        rect.height *= origScale; rect.width *= origScale;
        Mat img_crop = new Mat(inputColor, rect);

        Mat M = Imgproc.getRotationMatrix2D(new Point(rect.width/2, rect.height/2), -angle, 1.0);
        Mat img_rot = new Mat();
        Imgproc.warpAffine(img_crop, img_rot, M, rect.size());
        /* TODO: second round of cropping
            # rotate contour
            pts = numpy.int32(cv2.transform(numpy.array(c), M))
            #cv2.drawContours(img_rot,[pts],0,(0,255,255),2)
            rect = cv2.boundingRect(pts)

            # crop
            #
            (x, y, w, h) = rect
            img_crop = img_rot[y:(y+h), x:(x+w)]
         */

            Mat img_processed = findDigits(img_rot);

        Mat output = new Mat();
        // TODO: Silly to resize back
        Imgproc.resize(outputColor, output, inputSize, 0, 0, Imgproc.INTER_AREA);

        Rect roi = new Rect( new Point( 0, 0 ), new Size( inputSize.width/2, inputSize.height/2 ));
        Mat destinationROI = new Mat(output, roi );
        Imgproc.resize(img_processed, destinationROI, new Size( inputSize.width/2, inputSize.height/2 ), 0, 0, Imgproc.INTER_AREA);
        //img_rot.copyTo(destinationROI);

        //Imgproc.resize(img_rot, output, inputSize, 0, 0, Imgproc.INTER_AREA);
        return output;
    }
}