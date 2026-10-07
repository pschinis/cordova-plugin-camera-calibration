#import "CameraCalibration.h"
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>

// iOS has no per-device intrinsic calibration outside depth capture sessions (which the web view's
// capture isn't), so the calibration comes from the capture format: WebKit streams one of the
// camera's formats (or a scaling of one, keeping its shape), and a format's videoFieldOfView is the
// field of view across its width, the sensor's long side. The optical centre is taken as the
// frame's centre.
@implementation CameraCalibration

- (void)get:(CDVInvokedUrlCommand *)command {
    NSDictionary *options = [command argumentAtIndex:0 withDefault:@{} andClass:[NSDictionary class]];
    NSString *facing = [options[@"facing"] isKindOfClass:[NSString class]] ? options[@"facing"] : @"user";
    double width = [options[@"width"] doubleValue];
    double height = [options[@"height"] doubleValue];

    [self.commandDelegate runInBackground:^{
        NSString *error = nil;
        NSDictionary *calibration = [self calibrationForFacing:facing width:width height:height error:&error];
        CDVPluginResult *result = calibration
            ? [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:calibration]
            : [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

- (NSDictionary *)calibrationForFacing:(NSString *)facing width:(double)width height:(double)height error:(NSString **)error {
    if (width <= 0 || height <= 0) {
        *error = @"The stream's width and height are required";
        return nil;
    }
    AVCaptureDevicePosition position = [facing isEqualToString:@"environment"] ? AVCaptureDevicePositionBack : AVCaptureDevicePositionFront;
    AVCaptureDevice *device = [AVCaptureDevice defaultDeviceWithDeviceType:AVCaptureDeviceTypeBuiltInWideAngleCamera
                                                                 mediaType:AVMediaTypeVideo
                                                                  position:position];
    if (!device) {
        *error = [NSString stringWithFormat:@"No %@ camera", facing];
        return nil;
    }

    // Formats are in the sensor's (landscape) orientation; the stream may be portrait.
    double longSide = MAX(width, height);
    double shortSide = MIN(width, height);
    AVCaptureDeviceFormat *exact = nil;
    AVCaptureDeviceFormat *sameShape = nil;
    for (AVCaptureDeviceFormat *format in device.formats) {
        CMVideoDimensions dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription);
        if (dimensions.width <= 0 || dimensions.height <= 0) continue;
        if (dimensions.width == longSide && dimensions.height == shortSide) {
            if (!exact || format.videoFieldOfView > exact.videoFieldOfView) exact = format;
        } else if (fabs((double)dimensions.width / dimensions.height - longSide / shortSide) < 0.01) {
            // A scaling of a format: the same shape, the same field of view (the largest such format).
            CMVideoDimensions best = sameShape ? CMVideoFormatDescriptionGetDimensions(sameShape.formatDescription) : (CMVideoDimensions){ 0, 0 };
            if (dimensions.width > best.width) sameShape = format;
        }
    }
    AVCaptureDeviceFormat *format = exact ?: sameShape;
    if (!format) {
        *error = [NSString stringWithFormat:@"No %@ camera format matches a %.0fx%.0f stream", facing, width, height];
        return nil;
    }

    CMVideoDimensions dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription);
    double longFov = format.videoFieldOfView;
    double focal = (longSide / 2) / tan(longFov * M_PI / 360);
    double horizontalFov = 2 * atan((width / 2) / focal) * 180 / M_PI;
    double verticalFov = 2 * atan((height / 2) / focal) * 180 / M_PI;

    NSMutableDictionary *native = [@{
        @"deviceType": device.deviceType ?: [NSNull null],
        @"modelID": device.modelID ?: [NSNull null],
        @"localizedName": device.localizedName ?: [NSNull null],
        @"formatWidth": @(dimensions.width),
        @"formatHeight": @(dimensions.height),
        @"formatMatch": exact ? @"exact" : @"same_shape",
        @"videoFieldOfView": @(longFov),
    } mutableCopy];
    if (@available(iOS 13.0, *)) {
        native[@"geometricDistortionCorrectedVideoFieldOfView"] = @(format.geometricDistortionCorrectedVideoFieldOfView);
    }

    return @{
        @"source": @"measured",
        @"method": @"format_fov",
        @"platform": @"ios",
        @"width": @(width),
        @"height": @(height),
        @"intrinsics": @{ @"fx": @(focal), @"fy": @(focal), @"cx": @(width / 2), @"cy": @(height / 2) },
        @"horizontalFovDegrees": @(horizontalFov),
        @"verticalFovDegrees": @(verticalFov),
        @"native": native,
    };
}

@end
