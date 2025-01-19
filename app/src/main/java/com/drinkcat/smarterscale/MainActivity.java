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
            fcount = 0;
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

    private boolean overlapSegment(Rect a, Rect b) {
        Rect o = overlapRaw(a, b);

        // For vertical segments, accept slightly non-overlapping segments
        // to catch digits like 0 and 1 that don't have a vertical segment
        // in the center
        final double VERT_EXPAND_RATIO = 0.5;
        double th = 0.0;
        if (a.width < a.height && b.width < b.height)
            th = - VERT_EXPAND_RATIO * o.width;

        //Log.d(TAG, "overlap " + a + "/" + b + "=" + o);

        return (o.width >= 0 && o.height >= th);
    }

    private Rect union(Rect a, Rect b) {
        if (a == null) return b;
        if (b == null) return a;

        int x = Math.min(a.x, b.x);
        int y = Math.min(a.y, b.y);
        int width = Math.max(a.x+a.width, b.x+b.width) - x;
        int height = Math.max(a.y+a.height, b.y-b.height) - y;

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

    LinkedList<String> parsedText = new LinkedList<>();
    private Mat findDigits(Mat input) {
        /* TODO: original version needed 1000x1000 image... */
        Mat resizeInput = new Mat();
        Imgproc.resize(input, resizeInput, new Size(1000,1000), 0, 0, Imgproc.INTER_AREA);

        Mat output = resizeInput;

        Mat gray = new Mat();
        Imgproc.cvtColor(resizeInput, gray, Imgproc.COLOR_BGR2GRAY);
        // # TODO: Compute OTSU within display only
        Mat thresh = new Mat();
        Imgproc.threshold(gray, thresh, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        gray.release();
        Mat int_thresh = new Mat();
        Imgproc.integral(thresh, int_thresh);

        List<MatOfPoint> cnts = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();

        int kernelSize = 3;
        Mat element = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_RECT,
            new Size(2 * kernelSize + 1, 2 * kernelSize + 1),
                new Point(kernelSize, kernelSize));
        Mat tmp = new Mat();
        Imgproc.erode(thresh, tmp, element);
        //Imgproc.erode(tmp, thresh, element);
        //Imgproc.erode(thresh, tmp, element);
        Imgproc.dilate(tmp, thresh, element);
        //Imgproc.dilate(thresh, tmp, element);
        //Imgproc.dilate(tmp, thresh, element);
        tmp.release();

        output.release();
        Imgproc.cvtColor(thresh, output, Imgproc.COLOR_GRAY2BGRA);

        Imgproc.findContours(thresh, cnts, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        Imgproc.drawContours(output, cnts, -1, new Scalar(0,255,0), 2);

        LinkedList<TaggedRect> rectIgnore = new LinkedList<TaggedRect>();
        LinkedList<TaggedRect> rectGood = new LinkedList<TaggedRect>(); // Vertical and horizontal segments
        LinkedList<TaggedRect> rectDot = new LinkedList<TaggedRect>(); // Dots

        for (MatOfPoint c: cnts) {
            Rect rect = Imgproc.boundingRect(c);

            if (rect.width < 10 || rect.height < 10) {
                //rectIgnore.add(rect);
                //Log.d(TAG, "ignore too small: " + rect);
                continue;
            }

            if (rect.x < 10 || rect.y < 10 ||
                    rect.x + rect.width > thresh.size().width - 10 ||
                    rect.y + rect.height > thresh.size().height - 10) {
                //rectIgnore.add(rect);
                //Log.d(TAG, "ignore on the edge: " + rect + " // " + thresh.size());
                continue;
            }

            double h = rect.height;
            double w = rect.width;
            double ratio = (w > h) ? w/h : h/w;

            if (ratio > 1.1 && ratio < 2.0) {
                rectIgnore.add(new TaggedRect(rect));
                //Log.d(TAG, "ignore by ratio: " + rect + " / " + ratio);
                continue;
            }

            double area = sumRect(int_thresh, rect);
            double rect_area = rect.area();
            double fillratio = area / rect_area / 255;

            if (fillratio < 0.50) { // # Unlikely a segment?
                rectIgnore.add(new TaggedRect(rect));
                //Log.d(TAG, "ignore by fill ratio: " + rect + " // " + fillratio);
                continue;
            }

            if (ratio > 2.0)
                rectGood.add(new TaggedRect(rect));
            else
                rectDot.add(new TaggedRect(rect));
            //Log.d(TAG, "good: " + rect + " / " + ratio + " // " + fillratio);
        }

        for (Rect rect: rectIgnore)
            Imgproc.rectangle(output, rect, new Scalar(255,0,0), 3);

        for (Rect rect: rectGood)
            Imgproc.rectangle(output, rect, new Scalar(0,0,255), 3);

        for (Rect rect: rectDot)
            Imgproc.rectangle(output, rect, new Scalar(0,255,255), 10);

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
                        if (!overlapSegment(r2, r3)) continue;
                        r2.visited = true;
                        added = true;
                        break;
                    }
                    if (r2.visited) // Add outside the loop to avoid concurent modification
                        group.add(r2);
                }
            } while (added);
            groups.add(group);
        }

        /* Could be initialized only once */
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

        HashMap<Integer,String> DOTMAP = new HashMap<Integer, String>();
        DIGITMAP.put(sigArrayToSig(new int[]{0, 0, 0, 1}), ".");
        DIGITMAP.put(sigArrayToSig(new int[]{0, 1, 1, 0}), ":");

        HashMap<Integer,String> digits = new HashMap<Integer, String>();
        for (List<Rect> group: groups) {
            Rect u = null;
            for (Rect r : group)
                u = union(u, r);
            Imgproc.rectangle(output, u, new Scalar(255, 0, 255), 10);
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

            // Also look for nearby dots on the left and right
            int[][] dotsigarray = new int[2][4];
            for (TaggedRect r2: rectDot) {
                if (r2.visited) continue;
                // Half the diameter tolerance
                double tolerance = (r2.width+r2.height)/2.0/2.0;
                Rect o = overlapRaw(r2, u);
                if (o.width < -tolerance || o.height < -tolerance)
                    continue;

                r2.visited = true;
                int sx, sy;
                if (r2.x < u.x) // left
                    sx = 0;
                else if (r2.x+r2.width > u.x+u.width) // right
                    sx = 1;
                else
                    continue; //center?!

                sy = (int)Math.round(3.0*(r2.y-u.y)/u.height);
                if (sy < 0 || sy >= dotsigarray[sx].length)
                    continue;
                dotsigarray[sx][sy] = 1;
            }

            String leftdot = DIGITMAP.get(sigArrayToSig(dotsigarray[0]));
            String rightdot = DIGITMAP.get(sigArrayToSig(dotsigarray[1]));
            Log.d(TAG, "found dot: " + leftdot + "/" + rightdot +
                    " from " + Arrays.toString(dotsigarray[0]) + "/"  + Arrays.toString(dotsigarray[1]));

            if (leftdot != null)
                digit = leftdot + digit;
            if (rightdot != null)
                digit = digit + rightdot;
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
                Imgproc.FONT_HERSHEY_SIMPLEX, 10, new Scalar(128, 128, 255), 10);

        // TODO: Using a circular buffer would be better...
        parsedText.addFirst(s);
        while (parsedText.size() > 30)
            parsedText.removeLast();

        return output;
    }

    boolean init = false;

    int fcount = 0;
    double exposure = 0.0f;
     @Override
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        // FIXME: we like zoom, zoomies!
        if (!init) {
            List<Integer> zooms = mOpenCvCameraView.getZoomRatios();
            mOpenCvCameraView.setZoom(zooms.size() - 1);
            init = true;
            return inputFrame.rgba();
        }

        if (fcount == 0) {
            mOpenCvCameraView.setExposureLock(false);
            mOpenCvCameraView.setExposure(exposure);
        } else if (fcount >= 5) {
            //mOpenCvCameraView.setExposureLock(true);
            mOpenCvCameraView.setExposure(exposure);
        }
        fcount++;
        if (fcount > 100)
            fcount = 0;

        // FIXME: This assumes dimension is close to 1000x1000
        Mat inputColor = inputFrame.rgba();
        Mat inputGray = inputFrame.gray();
        Size inputSize = inputColor.size();
        Mat outputColor = new Mat(); /* output color frame that includes drawn shapes. */
        inputColor.copyTo(outputColor);

        Mat thresh = new Mat();
        Imgproc.threshold(inputGray, thresh, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
        //inputGray.release();

        List<MatOfPoint> cnts = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, cnts, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        thresh.release();

        // FIXME: This is ugly, I'm computing contourArea over and over again.
        cnts.sort(Comparator.comparingDouble((Mat c) -> Imgproc.contourArea(c)).reversed());

        boolean found = false;
        /* FIXME: Ugly pass-through */
        MatOfPoint2f c2f = null;
        double angle = 0.0;
        for (MatOfPoint c: cnts) {
            c2f = new MatOfPoint2f(c.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            RotatedRect rect = Imgproc.minAreaRect(c2f);
            Mat box = new Mat();
            Imgproc.boxPoints(rect, box);
            double h = rect.size.height;
            double w = rect.size.width;
            double rawangle = rect.angle;

            /* We're looking for a well-centered rectangle. */
            final double MINSIZE = 0.33 * inputSize.width;
            final double MAXSIZE = 0.66 * inputSize.width;
            final double MAXOFFCENTER = 0.25 * inputSize.width;
            if (h < MINSIZE || w < MINSIZE)
                continue;
            if (h > MAXSIZE || w > MAXSIZE)
                continue;
            if (Math.abs(rect.center.x-inputSize.width/2) > MAXOFFCENTER ||
                Math.abs(rect.center.y-inputSize.height/2) > MAXOFFCENTER)
                continue;

            double ratio = (w > h) ? w/h : h/w;
            /* Angle will be between 0 and 90 */
            if (rawangle > 45.0)
                angle = 90 - rawangle;
            else
                angle = rawangle;

            // TODO: Find the best one instead?
            Log.d(TAG, "h/w:" + h + "x" + w + "; angle=" + rawangle + "=>" + angle);
            if (ratio < 1.5 && Math.abs(angle) < 30) {
                found = true;
                break;
            }
        }

        // FIXME: c2f and angle are used below, be careful this is ugly
        Imgproc.drawContours(outputColor, cnts, -1, new Scalar(0,255,0), 3);
        Rect rect;
        if (found) {
            rect = Imgproc.boundingRect(c2f);
        } else {
            int rectSize = (int)(0.66*inputSize.width);
            rect = new Rect(
                    (int)(inputSize.width-rectSize)/2, (int)(inputSize.height-rectSize)/2,
                    rectSize, rectSize);
            angle = 0.0;
            c2f = null;
        }

        Mat contourmask = new Mat(inputSize, CvType.CV_8U);
        if (c2f != null) {
            MatOfPoint c = new MatOfPoint();
            c2f.convertTo(c, CvType.CV_32S);
            List<MatOfPoint> ctmp = new LinkedList<>(); ctmp.add(c);
            Imgproc.drawContours(contourmask, ctmp, -1, new Scalar(255), Imgproc.FILLED);
        } else {
            // Just mask the rectangle
            Imgproc.rectangle(contourmask, rect, new Scalar(255), Imgproc.FILLED);
        }
        Mat invcontourmask = new Mat();
        Core.bitwise_not(contourmask, invcontourmask);

        float histAvg = 0.0f;
         /* Compute histogram */
         {
             //inputGray = new Mat();
             //Imgproc.cvtColor(img_rot, inputGray, Imgproc.COLOR_BGR2GRAY);
             int histSize = 256;
             MatOfFloat histRange = new MatOfFloat(new float[]{0, 256});
             Mat hist = new Mat();
             List<Mat> inputGrayList = new LinkedList<>();
             inputGrayList.add(inputGray);
             Imgproc.calcHist(inputGrayList, new MatOfInt(0), contourmask, hist, new MatOfInt(histSize), histRange, false);
             float[] histData = new float[(int) (hist.total() * hist.channels())];
             hist.get(0, 0, histData);
             float histSum = 0.0f;
             histAvg = 0.0f;
             for (int i = 0; i < histSize; i++) {
                 histSum += histData[i];
                 histAvg += i*histData[i];
             }
             histAvg /= histSize;

             Log.d(TAG, "hist " + histSize + ":" + Arrays.toString(histData));
             final int basex = outputColor.width() - 256 * 2 - 10;
             final int basey = outputColor.height();
             Imgproc.line(outputColor,
                     new Point(basex-4, basey),
                     new Point(basex-4, basey - 10.0/256.0 * 500),
                     new Scalar(255, 255, 0), 2);
             Imgproc.line(outputColor,
                     new Point(basex+257*2, basey),
                     new Point(basex+257*2, basey - 10.0/256.0 * 500),
                     new Scalar(255, 255, 0), 2);
             for (int i = 0; i < histSize; i++) {
                 Imgproc.line(outputColor,
                         new Point(basex + i * 2, basey),
                         new Point(basex + i * 2, basey - Math.log(histData[i] / histSum * 500)*20),
                         new Scalar(255, 0, 0), 2);
             }

             /* Reduce exposure until we have no pixels > 200 */
             float histSumSaturated = 0.0f;
             float histSumMid = 0.0f;
             for (int i = 100; i < 200; i++)
                 histSumMid += histData[i];
             for (int i = 200; i < histSize; i++)
                 histSumSaturated += histData[i];
             if (histSumMid < histSum/3)
                 exposure = Math.min(exposure + 0.03, 1.0);
             if (histSumSaturated >= 1)
                 exposure = Math.max(exposure - 0.05, -1.0);
             Imgproc.putText(outputColor, "" + exposure, new Point(outputColor.width()-300, outputColor.height()-50),
                     Imgproc.FONT_HERSHEY_SIMPLEX, 2, new Scalar(255, 0, 0), 2);
             inputGray.release();
         }

         // Mask input with average value
         // TODO: would be better to compute otsu on masked region
         inputColor.setTo(new Scalar(histAvg,histAvg,histAvg), invcontourmask);
         //outputColor.setTo(new Scalar(histAvg,histAvg,histAvg), invcontourmask);

         contourmask.release();
         invcontourmask.release();

         /* End of compute histogram */

         Imgproc.rectangle(outputColor, rect, new Scalar(255,0,0), 5);
         Mat img_crop = new Mat(inputColor, rect);

         Mat M = Imgproc.getRotationMatrix2D(new Point(rect.width/2, rect.height/2), -angle, 1.0);
         Mat img_rot = new Mat();
         Imgproc.warpAffine(img_crop, img_rot, M, rect.size());
         img_crop.release();

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
        outputColor.release();
        double overlayRatio = 0.5;
        Size overlaySize = new Size( inputSize.width*overlayRatio, inputSize.height*overlayRatio );
        Rect roi = new Rect( new Point( 0, 0 ), overlaySize);
        Mat destinationROI = new Mat(output, roi );
        Imgproc.resize(img_processed, destinationROI, overlaySize, 0, 0, Imgproc.INTER_AREA);
        //img_rot.copyTo(destinationROI);

         /* Have we found a good readout yet? */
         HashMap<String, Integer> pcount = new HashMap<String, Integer>();
         for (String p: parsedText) {
             if (p.length() == 0)
                 continue;
             int cnt = pcount.getOrDefault(p, 0)+1;
             if (cnt > 10) {
                 Imgproc.putText(output, "<" + p + ">", new Point(0, output.size().height-50),
                         Imgproc.FONT_HERSHEY_SIMPLEX, 10, new Scalar(255, 0, 0), 30);
                 this.runOnUiThread(() -> {
                     (Toast.makeText(this, "Read out <" + p + ">!", Toast.LENGTH_LONG)).show();
                     startStop(false);
                 });
                 break;
             }
             pcount.put(p, cnt);
         }

        //Imgproc.resize(img_rot, output, inputSize, 0, 0, Imgproc.INTER_AREA);
         img_processed.release();
        return output;
    }
}