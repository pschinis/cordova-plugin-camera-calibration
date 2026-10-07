# cordova-plugin-camera-calibration

The lens calibration of the camera behind a `getUserMedia` stream: focal lengths and optical centre
in the stream's pixels, for projecting photos taken from it (the body scanner's
`@strongrfastr/body-image-projection`). Browsers don't expose any of it.

```js
const calibration = await window.plugins.cameraCalibration.get({
    facing: 'user',            // or 'environment'
    width: video.videoWidth,   // the stream's size, as the video element reports it
    height: video.videoHeight,
});
// { source: 'measured', method, platform, width, height,
//   intrinsics: { fx, fy, cx, cy }, horizontalFovDegrees, verticalFovDegrees, native }
```

`native` holds the camera's raw characteristics, for diagnostics. The promise rejects with a message
when the calibration can't be had (no such camera, no matching format, no calibration data).

## How

**iOS** (`method: 'format_fov'`): iOS only has per-device intrinsics in depth capture sessions,
which the web view's isn't. The front (or back) wide-angle camera's capture format matching the
stream's size (or, failing an exact one, of its shape: WebKit scales formats) gives
`videoFieldOfView`, the field of view across the format's long side; the focal length in pixels
follows, with the optical centre taken as the frame's centre.

**Android**: the first camera with the facing (as `getUserMedia` picks it), from its Camera2
characteristics:

- `method: 'intrinsic_calibration'`: `LENS_INTRINSIC_CALIBRATION`, the factory calibration (focal
  lengths and optical centre; in the pre-correction active array's coordinates from Android 9, the
  active array's before). Many, but not all, devices have it.
- `method: 'focal_length'`: otherwise the focal length and the sensor's physical size, with the
  optical centre at the frame's centre.

Either is mapped onto the stream: the active array cropped (centred) to the stream's shape, scaled
to its size, and turned upright by `SENSOR_ORIENTATION`.

Neither platform models lens distortion (Android's `LENS_DISTORTION` is returned in `native`).

## Caveats

- Which camera the web view streams is inferred (the facing's wide-angle camera on iOS, its first
  camera on Android), not read from the stream: phones with several front cameras may stream
  another.
- Video stabilization or other cropping the platform applies to the stream isn't accounted for.
