package com.strongrfastr.cameracalibration;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Size;
import android.util.SizeF;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The lens calibration of the camera behind a getUserMedia stream, from its Camera2
 * characteristics: the factory intrinsic calibration where the device provides it, else the focal
 * length and sensor size (with the optical centre at the frame's centre). Both are in sensor
 * coordinates, then mapped onto the stream: the active array cropped (centred) to the stream's
 * shape, scaled to its size and rotated upright by the sensor's orientation.
 */
public class CameraCalibration extends CordovaPlugin {

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if (!"get".equals(action)) return false;
        JSONObject options = args.optJSONObject(0);
        if (options == null) options = new JSONObject();
        final String facing = options.optString("facing", "user");
        final double width = options.optDouble("width", 0);
        final double height = options.optDouble("height", 0);
        cordova.getThreadPool().execute(() -> {
            try {
                callbackContext.success(calibration(facing, width, height));
            } catch (Exception e) {
                callbackContext.error(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
        return true;
    }

    private JSONObject calibration(String facing, double width, double height) throws CameraAccessException, JSONException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("The stream's width and height are required");
        CameraManager manager = (CameraManager) cordova.getActivity().getSystemService(Context.CAMERA_SERVICE);
        int wanted = "environment".equals(facing) ? CameraCharacteristics.LENS_FACING_BACK : CameraCharacteristics.LENS_FACING_FRONT;
        // The first camera facing that way, as getUserMedia picks for a facingMode.
        String[] ids = manager.getCameraIdList();
        int candidates = 0;
        String chosenId = null;
        CameraCharacteristics chosen = null;
        for (String id : ids) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing == null || lensFacing != wanted) continue;
            candidates++;
            if (chosen == null) {
                chosenId = id;
                chosen = characteristics;
            }
        }
        if (chosen == null) throw new IllegalStateException("No " + facing + " camera");

        Rect active = chosen.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Rect preCorrection = Build.VERSION.SDK_INT >= 23 ? chosen.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) : null;
        Size pixelArray = chosen.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        SizeF physical = chosen.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] focalLengths = chosen.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        Integer orientationValue = chosen.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int orientation = orientationValue != null ? orientationValue : 0;
        float[] intrinsic = Build.VERSION.SDK_INT >= 23 ? chosen.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) : null;
        float[] distortion = Build.VERSION.SDK_INT >= 28 ? chosen.get(CameraCharacteristics.LENS_DISTORTION) : null;
        if (active == null) throw new IllegalStateException("The camera reports no active array");

        // Intrinsics in active-array pixels (from its top left).
        double fx;
        double fy;
        double cx;
        double cy;
        String method;
        if (intrinsic != null && intrinsic.length >= 4 && intrinsic[0] > 0 && intrinsic[1] > 0) {
            // From Android 9, the calibration is in the pre-correction active array's coordinates;
            // before, the active array's.
            Rect origin = Build.VERSION.SDK_INT >= 28 && preCorrection != null ? preCorrection : active;
            fx = intrinsic[0];
            fy = intrinsic[1];
            cx = intrinsic[2] + origin.left - active.left;
            cy = intrinsic[3] + origin.top - active.top;
            method = "intrinsic_calibration";
        } else if (focalLengths != null && focalLengths.length > 0 && physical != null && pixelArray != null && physical.getWidth() > 0) {
            double pixelsPerMm = pixelArray.getWidth() / physical.getWidth();
            fx = fy = focalLengths[0] * pixelsPerMm;
            cx = active.width() / 2.0;
            cy = active.height() / 2.0;
            method = "focal_length";
        } else {
            throw new IllegalStateException("The camera reports neither an intrinsic calibration nor its focal length and sensor size");
        }

        // The stream in the sensor's orientation (a portrait stream from a landscape sensor is rotated).
        boolean rotated = (orientation == 90 || orientation == 270) && (width < height) == (active.width() > active.height());
        double sensorWidth = rotated ? height : width;
        double sensorHeight = rotated ? width : height;
        // The active array cropped (centred) to the stream's shape, then scaled to it.
        double cropWidth = active.width();
        double cropHeight = active.height();
        if (cropWidth / cropHeight > sensorWidth / sensorHeight) {
            cropWidth = cropHeight * sensorWidth / sensorHeight;
        } else {
            cropHeight = cropWidth * sensorHeight / sensorWidth;
        }
        double scale = sensorWidth / cropWidth;
        double sfx = fx * scale;
        double sfy = fy * scale;
        double scx = (cx - (active.width() - cropWidth) / 2) * scale;
        double scy = (cy - (active.height() - cropHeight) / 2) * scale;

        // Upright: the sensor image turned clockwise by its orientation (a quarter turn only when the
        // stream was turned to the other shape).
        int turn = rotated ? orientation : (orientation == 180 ? 180 : 0);
        double ufx = sfx;
        double ufy = sfy;
        double ucx = scx;
        double ucy = scy;
        if (turn == 90) {
            ufx = sfy;
            ufy = sfx;
            ucx = sensorHeight - scy;
            ucy = scx;
        } else if (turn == 180) {
            ucx = sensorWidth - scx;
            ucy = sensorHeight - scy;
        } else if (turn == 270) {
            ufx = sfy;
            ufy = sfx;
            ucx = scy;
            ucy = sensorWidth - scx;
        }

        JSONObject intrinsics = new JSONObject();
        intrinsics.put("fx", ufx);
        intrinsics.put("fy", ufy);
        intrinsics.put("cx", ucx);
        intrinsics.put("cy", ucy);

        JSONObject nativeInfo = new JSONObject();
        nativeInfo.put("cameraId", chosenId);
        nativeInfo.put("camerasFacing", candidates);
        nativeInfo.put("sensorOrientation", orientation);
        nativeInfo.put("activeArray", rect(active));
        if (preCorrection != null) nativeInfo.put("preCorrectionActiveArray", rect(preCorrection));
        if (pixelArray != null) nativeInfo.put("pixelArray", new JSONArray().put(pixelArray.getWidth()).put(pixelArray.getHeight()));
        if (physical != null) nativeInfo.put("physicalSizeMm", new JSONArray().put(physical.getWidth()).put(physical.getHeight()));
        if (focalLengths != null) nativeInfo.put("focalLengthsMm", floats(focalLengths));
        if (intrinsic != null) nativeInfo.put("intrinsicCalibration", floats(intrinsic));
        if (distortion != null) nativeInfo.put("distortion", floats(distortion));
        nativeInfo.put("streamCrop", new JSONArray().put(cropWidth).put(cropHeight));
        nativeInfo.put("rotated", rotated);
        nativeInfo.put("model", Build.MODEL);
        nativeInfo.put("manufacturer", Build.MANUFACTURER);
        nativeInfo.put("sdk", Build.VERSION.SDK_INT);

        JSONObject result = new JSONObject();
        result.put("source", "measured");
        result.put("method", method);
        result.put("platform", "android");
        result.put("width", width);
        result.put("height", height);
        result.put("intrinsics", intrinsics);
        result.put("horizontalFovDegrees", Math.toDegrees(2 * Math.atan((width / 2) / ufx)));
        result.put("verticalFovDegrees", Math.toDegrees(2 * Math.atan((height / 2) / ufy)));
        result.put("native", nativeInfo);
        return result;
    }

    private static JSONArray rect(Rect r) {
        return new JSONArray().put(r.left).put(r.top).put(r.width()).put(r.height());
    }

    private static JSONArray floats(float[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (float value : values) array.put((double) value);
        return array;
    }
}
