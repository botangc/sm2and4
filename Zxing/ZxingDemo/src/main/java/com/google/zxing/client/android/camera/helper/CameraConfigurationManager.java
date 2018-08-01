package com.google.zxing.client.android.camera.helper;

import android.content.Context;
import android.graphics.Point;
import android.hardware.Camera;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.google.zxing.client.android.camera.open.CameraFacing;
import com.google.zxing.client.android.camera.open.OpenCamera;
import com.zxing.demo.MainApplication;
import com.zxing.demo.capture.AmbientLightHelper;
import com.zxing.demo.manager.DBManager;

/**
 * A class which deals with reading, parsing, and setting the camera parameters which are used to
 * configure the camera hardware.
 */
@SuppressWarnings("deprecation") // camera APIs
public final class CameraConfigurationManager {
	
	private static final String TAG = "CameraConfiguration";
	
	private int cwNeededRotation;
	private int cwRotationFromDisplayToCamera;
	private Point screenResolution;
	private Point cameraResolution;
	private Point bestPreviewSize;
	private Point previewSizeOnScreen;
	
	public CameraConfigurationManager() {
	}
	
	/**
	 * Reads, one time, values from the camera that are needed by the app.
	 */
	public void initFromCameraParameters(OpenCamera camera) {
		Camera.Parameters parameters = camera.getCamera().getParameters();
		WindowManager manager = (WindowManager) MainApplication.getApplication().getSystemService(Context.WINDOW_SERVICE);
		Display display = manager.getDefaultDisplay();
		
		int displayRotation = display.getRotation();
		int cwRotationFromNaturalToDisplay;
		switch (displayRotation) {
			case Surface.ROTATION_0:
				cwRotationFromNaturalToDisplay = 0;
				break;
			case Surface.ROTATION_90:
				cwRotationFromNaturalToDisplay = 90;
				break;
			case Surface.ROTATION_180:
				cwRotationFromNaturalToDisplay = 180;
				break;
			case Surface.ROTATION_270:
				cwRotationFromNaturalToDisplay = 270;
				break;
			default:
				// Have seen this return incorrect values like -90
				if (displayRotation % 90 == 0) {
					cwRotationFromNaturalToDisplay = (360 + displayRotation) % 360;
				} else {
					throw new IllegalArgumentException("Bad rotation: " + displayRotation);
				}
		}
		Log.i(TAG, "Display at: " + cwRotationFromNaturalToDisplay);
		
		int cwRotationFromNaturalToCamera = camera.getOrientation();
		Log.i(TAG, "Camera at: " + cwRotationFromNaturalToCamera);
		
		// Still not 100% sure about this. But acts like we need to flip this:
		if (camera.getFacing() == CameraFacing.FRONT) {
			cwRotationFromNaturalToCamera = (360 - cwRotationFromNaturalToCamera) % 360;
			Log.i(TAG, "Front camera overriden to: " + cwRotationFromNaturalToCamera);
		}
		
		cwRotationFromDisplayToCamera = (360 + cwRotationFromNaturalToCamera - cwRotationFromNaturalToDisplay) % 360;
		Log.i(TAG, "Final display orientation: " + cwRotationFromDisplayToCamera);
		if (camera.getFacing() == CameraFacing.FRONT) {
			Log.i(TAG, "Compensating rotation for front camera");
			cwNeededRotation = (360 - cwRotationFromDisplayToCamera) % 360;
		} else {
			cwNeededRotation = cwRotationFromDisplayToCamera;
		}
		Log.i(TAG, "Clockwise rotation from display to camera: " + cwNeededRotation);
		
		Point theScreenResolution = new Point();
		display.getSize(theScreenResolution);
		screenResolution = theScreenResolution;
		Log.i(TAG, "Screen resolution in current orientation: " + screenResolution);
		cameraResolution = CameraConfigurationUtils.findBestPreviewSizeValue(parameters, screenResolution);
		Log.i(TAG, "Camera resolution: " + cameraResolution);
		bestPreviewSize = CameraConfigurationUtils.findBestPreviewSizeValue(parameters, screenResolution);
		Log.i(TAG, "Best available preview size: " + bestPreviewSize);
		
		boolean isScreenPortrait = screenResolution.x < screenResolution.y;
		boolean isPreviewSizePortrait = bestPreviewSize.x < bestPreviewSize.y;
		
		if (isScreenPortrait == isPreviewSizePortrait) {
			previewSizeOnScreen = bestPreviewSize;
		} else {
			previewSizeOnScreen = new Point(bestPreviewSize.y, bestPreviewSize.x);
		}
		Log.i(TAG, "Preview size on screen: " + previewSizeOnScreen);
	}
	
	public void setDesiredCameraParameters(OpenCamera camera, boolean safeMode) {
		
		Camera theCamera = camera.getCamera();
		Camera.Parameters parameters = theCamera.getParameters();
		
		if (parameters == null) {
			Log.w(TAG, "Device error: no camera parameters are available. Proceeding without configuration.");
			return;
		}
		
		Log.i(TAG, "Initial camera parameters: " + parameters.flatten());
		
		if (safeMode) {
			Log.w(TAG, "In camera config safe mode -- most settings will not be honored");
		}
		
		initializeTorch(parameters, safeMode);
		
		CameraConfigurationUtils.setFocus(parameters, DBManager.getInstance().getAutoFocus(), DBManager.getInstance().getDisableContinuousFocus(), safeMode);
		
		if (!safeMode) {
			if (DBManager.getInstance().getInvertScan()) {
				CameraConfigurationUtils.setInvertColor(parameters);
			}
			
			if (!DBManager.getInstance().getDisableBarcodeSceneMode()) {
				CameraConfigurationUtils.setBarcodeSceneMode(parameters);
			}
			
			if (!DBManager.getInstance().getDisableMetering()) {
				CameraConfigurationUtils.setVideoStabilization(parameters);
				CameraConfigurationUtils.setFocusArea(parameters);
				CameraConfigurationUtils.setMetering(parameters);
			}
			
			//SetRecordingHint to true also a workaround for low framerate on Nexus 4
			//https://stackoverflow.com/questions/14131900/extreme-camera-lag-on-nexus-4
			parameters.setRecordingHint(true);
			
		}
		
		parameters.setPreviewSize(bestPreviewSize.x, bestPreviewSize.y);
		
		theCamera.setParameters(parameters);
		
		theCamera.setDisplayOrientation(cwRotationFromDisplayToCamera);
		
		Camera.Parameters afterParameters = theCamera.getParameters();
		Camera.Size afterSize = afterParameters.getPreviewSize();
		if (afterSize != null && (bestPreviewSize.x != afterSize.width || bestPreviewSize.y != afterSize.height)) {
			Log.w(TAG, "Camera said it supported preview size " + bestPreviewSize.x + 'x' + bestPreviewSize.y + ", but after setting it, preview size is " + afterSize.width + 'x' + afterSize.height);
			bestPreviewSize.x = afterSize.width;
			bestPreviewSize.y = afterSize.height;
		}
	}
	
	Point getBestPreviewSize() {
		return bestPreviewSize;
	}
	
	Point getPreviewSizeOnScreen() {
		return previewSizeOnScreen;
	}
	
	public Point getCameraResolution() {
		return cameraResolution;
	}
	
	public Point getScreenResolution() {
		return screenResolution;
	}
	
	int getCWNeededRotation() {
		return cwNeededRotation;
	}
	
	public boolean getTorchState(Camera camera) {
		if (camera != null) {
			Camera.Parameters parameters = camera.getParameters();
			if (parameters != null) {
				String flashMode = parameters.getFlashMode();
				return Camera.Parameters.FLASH_MODE_ON.equals(flashMode) || Camera.Parameters.FLASH_MODE_TORCH.equals(flashMode);
			}
		}
		return false;
	}
	
	public void setTorch(Camera camera, boolean newSetting) {
		Camera.Parameters parameters = camera.getParameters();
		doSetTorch(parameters, newSetting, false);
		camera.setParameters(parameters);
	}
	
	private void initializeTorch(Camera.Parameters parameters, boolean safeMode) {
		boolean currentSetting = (AmbientLightHelper.getLightMode() == AmbientLightHelper.LightMode.ON);
		doSetTorch(parameters, currentSetting, safeMode);
	}
	
	private void doSetTorch(Camera.Parameters parameters, boolean newSetting, boolean safeMode) {
		CameraConfigurationUtils.setTorch(parameters, newSetting);
		if (!safeMode && !DBManager.getInstance().getDisableExposure()) {
			CameraConfigurationUtils.setBestExposure(parameters, newSetting);
		}
	}
	
}