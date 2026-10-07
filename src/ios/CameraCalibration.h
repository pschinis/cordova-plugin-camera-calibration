#import <Cordova/CDVPlugin.h>

/**
 * The lens calibration of the camera behind a getUserMedia stream: the capture format matching the
 * stream's size, and its field of view.
 */
@interface CameraCalibration : CDVPlugin

- (void)get:(CDVInvokedUrlCommand *)command;

@end
