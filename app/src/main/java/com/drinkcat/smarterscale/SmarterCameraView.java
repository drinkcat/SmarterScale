package com.drinkcat.smarterscale;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.opencv.android.JavaCameraView;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Log;

public class SmarterCameraView extends JavaCameraView implements Camera.PictureCallback {

    private static final String TAG = "SmarterCameraView";
    private String mPictureFileName;

    public SmarterCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public List<String> getEffectList() {
        return mCamera.getParameters().getSupportedColorEffects();
    }

    public boolean isEffectSupported() {
        return (mCamera.getParameters().getColorEffect() != null);
    }

    public String getEffect() {
        return mCamera.getParameters().getColorEffect();
    }

    public void setEffect(String effect) {
        Camera.Parameters params = mCamera.getParameters();
        params.setColorEffect(effect);
        mCamera.setParameters(params);
    }

    public void setZoom(int zoom) {
        Camera.Parameters params = mCamera.getParameters();
        params.setZoom(zoom);
        mCamera.setParameters(params);
    }

    public void setExposure(double exposure) {
        Camera.Parameters params = mCamera.getParameters();
        int value;
        if (exposure > 0)
            value = (int)Math.round(- params.getMinExposureCompensation() * exposure);
        else
            value = (int)Math.round(params.getMaxExposureCompensation() * exposure);
        params.setExposureCompensation(value);

        // TODO: That doesn't seem to do much?!
        List<Camera.Area> meteringAreas = new LinkedList<>();
        meteringAreas.add(new Camera.Area(new Rect(-100, -100, 100, 100), 100));
        params.setMeteringAreas(meteringAreas);

        List<int[]> fps = params.getSupportedPreviewFpsRange();
        int[] fp = new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE };

        /* Slowest range, but still more than 10 fps */
        for (int[] fpi: fps) {
            Log.d(TAG, "fps " + Arrays.toString(fpi));
            if (fpi[1] >= 10000 && fpi[1] < fp[1]) {
                fp[0] = fpi[0];
                fp[1] = fpi[1];
            }
        }
        Log.d(TAG, "fps range set to " + Arrays.toString(fp));
        params.setPreviewFpsRange(fp[0], fp[1]);

        mCamera.setParameters(params);
    }

    public void setExposureLock(boolean lock) {
        Camera.Parameters params = mCamera.getParameters();
        params.setAutoExposureLock(lock);
        mCamera.setParameters(params);
    }

    public List<Integer> getZoomRatios() { return mCamera.getParameters().getZoomRatios(); }

    public List<Camera.Size> getResolutionList() {
        return mCamera.getParameters().getSupportedPreviewSizes();
    }

    public void setResolution(Camera.Size resolution) {
        disconnectCamera();
        mMaxHeight = resolution.height;
        mMaxWidth = resolution.width;
        connectCamera(getWidth(), getHeight());
    }

    public Camera.Size getResolution() {
        return mCamera.getParameters().getPreviewSize();
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {

    }
}

