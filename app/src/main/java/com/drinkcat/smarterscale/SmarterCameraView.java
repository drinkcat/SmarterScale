package com.drinkcat.smarterscale;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.opencv.android.JavaCameraView;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.util.Log;

public class SmarterCameraView extends JavaCameraView implements Camera.PictureCallback {

    private static final String TAG = "SmarterCameraView";

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

    public void setZoom(double zoom) {
        if (mCamera == null || !Double.isFinite(zoom))
            return;
        Camera.Parameters params = mCamera.getParameters();
        zoom = Math.min(zoom, 1.0);
        zoom = Math.max(zoom, 0.0);
        params.setZoom((int)(zoom * params.getMaxZoom()));
        mCamera.setParameters(params);
    }

    public double getZoom() {
        if (mCamera == null)
            return Double.NaN;
        Camera.Parameters params = mCamera.getParameters();
        return (double)params.getZoom()/params.getMaxZoom();
    }

    public void setExposure(double exposure) {
        if (mCamera == null)
            return;
        Camera.Parameters params = mCamera.getParameters();
        int value;
        if (exposure < 0)
            value = (int)Math.round(- params.getMinExposureCompensation() * exposure);
        else
            value = (int)Math.round(params.getMaxExposureCompensation() * exposure);
        params.setExposureCompensation(value);

        // TODO: That doesn't seem to change much?!
        List<Camera.Area> meteringAreas = new LinkedList<>();
        meteringAreas.add(new Camera.Area(new Rect(-100, -100, 100, 100), 100));
        params.setMeteringAreas(meteringAreas);

        mCamera.setParameters(params);
    }

    /* Set fps to the minimum provided. */
    public void setSlowFps() {
        if (mCamera == null)
            return;
        Camera.Parameters params = mCamera.getParameters();
        /* Set slowest fps range, but still more than 10 fps */
        int[] fp = new int[] { Integer.MAX_VALUE, Integer.MAX_VALUE };
        List<int[]> fps = params.getSupportedPreviewFpsRange();
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
        if (mCamera == null)
            return;
        Camera.Parameters params = mCamera.getParameters();
        params.setAutoExposureLock(lock);
        mCamera.setParameters(params);
    }

    public List<Camera.Size> getResolutionList() {
        if (mCamera == null)
            return null;
        return mCamera.getParameters().getSupportedPreviewSizes();
    }

    public void setResolution(Camera.Size resolution) {
        if (mCamera == null)
            return;
        disconnectCamera();
        mMaxHeight = resolution.height;
        mMaxWidth = resolution.width;
        connectCamera(getWidth(), getHeight());
    }

    public Camera.Size getResolution() {
        if (mCamera == null)
            return null;
        return mCamera.getParameters().getPreviewSize();
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {

    }
}

