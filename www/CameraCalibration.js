var exec = require('cordova/exec');

/**
 * The lens calibration of the camera behind a getUserMedia stream, in the stream's pixels.
 *
 * options: { facing: 'user' | 'environment', width, height } (the stream's size, as the video
 * element reports it). Resolves { source: 'measured', method, platform, width, height,
 * intrinsics: { fx, fy, cx, cy }, horizontalFovDegrees, verticalFovDegrees, native } (`native`: the
 * camera's raw characteristics, for diagnostics), or rejects with a message.
 */
exports.get = function (options) {
    return new Promise(function (resolve, reject) {
        exec(resolve, reject, 'CameraCalibration', 'get', [options || {}]);
    });
};
