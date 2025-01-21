package com.drinkcat.smarterscale;

import android.util.Log;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class Digitizer {
    private static final String TAG = "Digitizer";

    private HashMap<Integer,String> DIGITMAP;

    /* TODO: This would be better as a circular buffer. */
    private LinkedList<String> parsedText;
    public Digitizer() {
        initDigitMap();

        /* TODO: setup configurable parameters. */
        parsedText = new LinkedList<>();
    }

    public void reset() {
        parsedText.clear();
    }

    public String getParsedText() {
        HashMap<String, Integer> pcount = new HashMap<String, Integer>();
        for (String p: parsedText) {
            if (p.isEmpty())
                continue;
            int cnt = pcount.getOrDefault(p, 0) + 1;
            if (cnt > 10)
                return p;
            pcount.put(p, cnt);
        }
        return null;
    }

    /* Parse frame. input is grayscale, output is color and will be painted on for debugging. */
    public void parseFrame(Mat input, Mat output) {
        Size inputSize = input.size();

        /* We only process the center 2/3 of the image. */
        final double ROI_SIZE = 0.66;
        int roi_size = (int)(Math.min(inputSize.width, inputSize.height) * ROI_SIZE);
        Rect crop = new Rect(
                (int)(inputSize.width - roi_size)/2, (int)(inputSize.height - roi_size)/ 2,
                roi_size, roi_size);
        Mat inputGray = input.submat(crop);
        Mat outputCrop = output.submat(crop);

        /* Blur, threshold and transform. */
        Mat thresh = threshold(inputGray, inputSize);

        /* Get contours */
        List<MatOfPoint> cnts = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, cnts, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
        /* Draw contours for debugging. */
        Imgproc.drawContours(outputCrop, cnts, -1, new Scalar(0,128,0), 2);

        /* Integral of threshold, used for fill ratio later. */
        Mat int_thresh = new Mat();
        Imgproc.integral(thresh, int_thresh);
        thresh.release();

        LinkedList<TaggedRect> segments = findSegments(outputCrop, int_thresh, cnts);
        thresh.release();
        findDigits(outputCrop, segments);

        /* Draw guiding rectangle for the user. */
        int min_height = (int)(inputSize.height*MIN_ASSUMED_HEIGHT);
        int max_height = (int)(inputSize.height*MAX_ASSUMED_HEIGHT);
        Rect rect = new Rect(crop.x, (int)(inputSize.height-min_height)/2, crop.width, min_height);
        Imgproc.rectangle(output, rect, new Scalar(255,0,0), (int)(0.005*inputSize.width));
        rect = new Rect(crop.x, (int)(inputSize.height-max_height)/2, crop.width, max_height);
        Imgproc.rectangle(output, rect, new Scalar(255,0,0), (int)(0.005*inputSize.width));
    }

    /* Blur, threshold and transform. */
    /* inputSize is the original size before cropping. */
    private Mat threshold(Mat inputGray, Size inputSize) {
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

        return thresh;
    }

    private void findDigits(Mat output, LinkedList<TaggedRect> segments) {
        LinkedList<List<Rect>> groups = new LinkedList<List<Rect>>();

        // Extract digits (can most likely be optimized)
        for (TaggedRect r1: segments) {
            if (r1.visited) continue;
            r1.visited = true;
            LinkedList<Rect> group = new LinkedList<>();
            group.add(r1);
            boolean added;
            do {
                added = false;
                for (TaggedRect r2: segments) {
                    if (r2.visited) continue;
                    for (Rect r3: group) {
                        if (!RectUtils.overlapCheck(r2, r3)) continue;
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

        // Now find out which digit it is based on segment location
        HashMap<Integer,String> digits = new HashMap<Integer, String>();
        for (List<Rect> group: groups) {
            Rect u = null;
            for (Rect r : group)
                u = RectUtils.union(u, r);
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

        // Sort digits by x position, and output parsed string.
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

    private LinkedList<TaggedRect> findSegments(Mat output, Mat int_thresh, List<MatOfPoint> cnts) {
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

            double area = RectUtils.sumRect(int_thresh, rect);
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

        return rectGood;
    }

    /** Segment to digit logic. **/
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

    /* TODO: DIGITMAP could be static. */
    private void initDigitMap() {
        DIGITMAP = new HashMap<Integer, String>();
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
    }

    /*** Constants, some of those should be configurable. ***/
    /* We want to find digits that take 20-35% of the image height. */
    private final double MIN_ASSUMED_HEIGHT = 0.20;
    private final double MAX_ASSUMED_HEIGHT = 0.35;

    /* Vertical segments tend to be about half the size. */
    /* TODO: Configurable. */
    private final double SEGMENT_VERT_HORIZ_RATIO = 0.5;
    /* Vertical segment aspect ratio. */
    private final double MIN_VERT_SEGMENT_RATIO = 1.67;
    private final double MAX_VERT_SEGMENT_RATIO = 2.67;
    /* Horizontal segment aspect ratio. */
    private final double MIN_HORIZ_SEGMENT_RATIO = 3.0;
    private final double MAX_HORIZ_SEGMENT_RATIO = 4.5;
    /* /TODO: Configurable */

    /* 0.9: Segments can be shorter than half the height */
    private final double MIN_VERT_SEGMENT_HEIGHT = MIN_ASSUMED_HEIGHT * 0.5 * 0.9;
    private final double MAX_VERT_SEGMENT_HEIGHT = MAX_ASSUMED_HEIGHT * 0.5;

    private final double MIN_HORIZ_SEGMENT_HEIGHT = MIN_VERT_SEGMENT_HEIGHT * SEGMENT_VERT_HORIZ_RATIO;
    private final double MAX_HORIZ_SEGMENT_HEIGHT = MAX_ASSUMED_HEIGHT * SEGMENT_VERT_HORIZ_RATIO / 0.8;

    /* TODO: Configurable. */
    private final double MIN_FILL_RATIO = 0.7;

    /*** End of constants. ***/
}
