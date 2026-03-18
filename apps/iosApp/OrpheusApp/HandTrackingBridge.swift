#if canImport(MediaPipeTasksVision)
import MediaPipeTasksVision
import AVFoundation

@objc public class HandTrackingBridge: NSObject {
    private var handLandmarker: HandLandmarker?
    private var captureSession: AVCaptureSession?
    private var onLandmarksCallback: (([[NSNumber]]) -> Void)?

    @objc public func start(onLandmarks: @escaping ([[NSNumber]]) -> Void) {
        onLandmarksCallback = onLandmarks

        // Initialize HandLandmarker
        guard let modelPath = Bundle.main.path(forResource: "hand_landmarker", ofType: "task") else {
            print("[HandTracking] Model not found")
            return
        }

        let options = HandLandmarkerOptions()
        options.baseOptions.modelAssetPath = modelPath
        options.runningMode = .liveStream
        options.numHands = 2
        options.handLandmarkerLiveStreamDelegate = self

        do {
            handLandmarker = try HandLandmarker(options: options)
        } catch {
            print("[HandTracking] Failed to create HandLandmarker: \(error)")
            return
        }

        // Set up camera capture
        captureSession = AVCaptureSession()
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let input = try? AVCaptureDeviceInput(device: device) else {
            print("[HandTracking] Camera not available")
            return
        }

        captureSession?.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(self, queue: DispatchQueue(label: "handtracking"))
        captureSession?.addOutput(output)
        captureSession?.startRunning()
    }

    @objc public func stop() {
        captureSession?.stopRunning()
        captureSession = nil
        handLandmarker = nil
    }
}

extension HandTrackingBridge: AVCaptureVideoDataOutputSampleBufferDelegate {
    public func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let mpImage = try? MPImage(pixelBuffer: imageBuffer)
        guard let image = mpImage else { return }
        let timestamp = Int(CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds * 1000)
        try? handLandmarker?.detectAsync(image: image, timestampInMilliseconds: timestamp)
    }
}

extension HandTrackingBridge: HandLandmarkerLiveStreamDelegate {
    public func handLandmarker(
        _ handLandmarker: HandLandmarker,
        didFinishDetection result: HandLandmarkerResult?,
        timestampInMilliseconds: Int,
        error: Error?
    ) {
        guard let result = result, let landmarks = result.landmarks.first else { return }
        let landmarkArray = landmarks.map { landmark -> [NSNumber] in
            [NSNumber(value: landmark.x), NSNumber(value: landmark.y), NSNumber(value: landmark.z)]
        }
        DispatchQueue.main.async { [weak self] in
            self?.onLandmarksCallback?(landmarkArray)
        }
    }
}
#else
// Stub when MediaPipe is not available
import Foundation

@objc public class HandTrackingBridge: NSObject {
    @objc public func start(onLandmarks: @escaping ([[NSNumber]]) -> Void) {
        print("[HandTracking] MediaPipe not available — hand tracking disabled")
    }

    @objc public func stop() {}
}
#endif
