package com.drinkcat.smarterscale;

import android.os.Bundle;
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
import java.util.List;

public class MainActivity extends CameraActivity implements CvCameraViewListener2 {
    private static final String TAG = "MainActivity";

    private CameraBridgeViewBase mOpenCvCameraView;

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

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.tutorial1_activity_java_surface_view);

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

    @Override
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
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

        // TODO: Sort the contours?
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
            angle = rect.angle;
            if (h < 100 && w < 100)
                continue;

            double ratio = (w > h) ? w/h : h/w;
            angle = ((angle + 360) % 90) - 90;
            // TODO: Find the best one instead?
            Log.d(TAG, "h/w:" + h + "x" + w + "; angle" + angle);
            if (h > 100 && w > 100 && ratio < 1.5 && Math.abs(angle) < 30) {
                found = true;
                break;
            }
        }

        Imgproc.drawContours(outputColor, cnts, -1, new Scalar(0,255,0), 3);
        if (!found) {
            Mat output = new Mat();
            // TODO: Silly to resize back
            Imgproc.resize(outputColor, output, inputSize, 0, 0, Imgproc.INTER_AREA);
            return output;
        }


        // FIXME: c, angle, and box are used below, be careful this is ugly
        Rect rect = Imgproc.boundingRect(c2f);
        rect.x *= origScale; rect.y *= origScale;
        rect.height *= origScale; rect.width *= origScale;
        Mat img_crop = new Mat(inputColor, rect);

        Mat M = Imgproc.getRotationMatrix2D(new Point(rect.width/2, rect.height/2), angle, 1.0);
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

        Imgproc.drawContours(outputColor, cnts, -1, new Scalar(0,255,0), 3);
        Mat output = new Mat();
        // TODO: Silly to resize back
        Imgproc.resize(outputColor, output, inputSize, 0, 0, Imgproc.INTER_AREA);
        Imgproc.resize(img_rot, output, inputSize, 0, 0, Imgproc.INTER_AREA);
        return output;
    }
}