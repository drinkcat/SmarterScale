package com.drinkcat.smarterscale;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

import org.opencv.android.JavaCameraView;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
            value = Math.round(- params.getMinExposureCompensation() * exposure);
        else
            value = Math.round(params.getMaxExposureCompensation() * exposure);
        params.setExposureCompensation(value);
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

