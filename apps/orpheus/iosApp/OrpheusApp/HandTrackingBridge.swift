import Foundation
import OrpheusShared

#if canImport(MediaPipeTasksVision)
import AVFoundation
import MediaPipeTasksVision
import UIKit

/// Owns the front camera and MediaPipe's HandLandmarker, and pushes results into Kotlin's
/// [IosHandTracker]. Kotlin decides when the camera runs (`onRunningChanged`); this owns the hardware.
///
/// Frames are rotated upright and mirrored at the capture connection, so the preview frame and
/// the landmarks share one coordinate space and handedness matches the other platforms.
@objc public final class HandTrackingBridge: NSObject {
    private let tracker: IosHandTracker
    private let sessionQueue = DispatchQueue(label: "org.balch.orpheus.handtracking.session")
    private let videoQueue = DispatchQueue(label: "org.balch.orpheus.handtracking.video")

    // sessionQueue only.
    private var session: AVCaptureSession?
    private var connection: AVCaptureConnection?
    private var rotationCoordinator: NSObject?
    private var rotationObservation: NSKeyValueObservation?

    // videoQueue only: the sample-buffer callback reads these every frame.
    private var landmarker: HandLandmarker?
    private var lastTimestampMs = -1

    /// Hands per frame, matching the desktop and Android trackers.
    private static let maxHands = 2
    private static let floatsPerHand = 1 + 21 * 3

    @objc public init(tracker: IosHandTracker) {
        self.tracker = tracker
        super.init()
        let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
        tracker.setAvailable(available: device != nil)
    }

    @objc public func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            sessionQueue.async { self.startSession() }
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if granted { self.sessionQueue.async { self.startSession() } }
            }
        default:
            NSLog("[HandTracking] camera access denied")
        }
    }

    @objc public func stop() {
        sessionQueue.async {
            self.session?.stopRunning()
            self.session = nil
            self.connection = nil
            self.rotationObservation = nil
            self.rotationCoordinator = nil
            self.videoQueue.async { self.landmarker = nil }
        }
    }

    // Runs on sessionQueue.
    private func startSession() {
        guard session == nil else { return }
        guard let landmarker = makeLandmarker() else { return }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
              let input = try? AVCaptureDeviceInput(device: device) else {
            NSLog("[HandTracking] front camera unavailable")
            return
        }

        let session = AVCaptureSession()
        session.beginConfiguration()
        session.sessionPreset = .vga640x480
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: videoQueue)
        guard session.canAddInput(input), session.canAddOutput(output) else {
            NSLog("[HandTracking] cannot configure capture session")
            return
        }
        session.addInput(input)
        session.addOutput(output)

        if let connection = output.connection(with: .video) {
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = true
            }
            self.connection = connection
            trackRotation(of: device, connection: connection)
        }
        session.commitConfiguration()

        videoQueue.sync {
            self.landmarker = landmarker
            self.lastTimestampMs = -1
        }
        self.session = session
        session.startRunning()
    }

    private func makeLandmarker() -> HandLandmarker? {
        guard let modelPath = Bundle.main.path(forResource: "hand_landmarker", ofType: "task") else {
            NSLog("[HandTracking] hand_landmarker.task missing from the app bundle")
            return nil
        }
        let options = HandLandmarkerOptions()
        options.baseOptions.modelAssetPath = modelPath
        options.runningMode = .liveStream
        options.numHands = Self.maxHands
        options.handLandmarkerLiveStreamDelegate = self
        do {
            return try HandLandmarker(options: options)
        } catch {
            NSLog("[HandTracking] HandLandmarker failed: \(error)")
            return nil
        }
    }

    /// Keeps frames upright as the iPad rotates.
    private func trackRotation(of device: AVCaptureDevice, connection: AVCaptureConnection) {
        if #available(iOS 17.0, *) {
            let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
            rotationCoordinator = coordinator
            let apply = { [weak connection] (angle: CGFloat) in
                guard let connection, connection.isVideoRotationAngleSupported(angle) else { return }
                connection.videoRotationAngle = angle
            }
            apply(coordinator.videoRotationAngleForHorizonLevelCapture)
            rotationObservation = coordinator.observe(
                \.videoRotationAngleForHorizonLevelCapture, options: [.new]
            ) { [weak self] _, change in
                guard let angle = change.newValue else { return }
                self?.sessionQueue.async { apply(angle) }
            }
        } else {
            DispatchQueue.main.async {
                let orientation = UIApplication.shared.connectedScenes
                    .compactMap { ($0 as? UIWindowScene)?.interfaceOrientation }.first ?? .portrait
                self.sessionQueue.async { Self.applyLegacyOrientation(orientation, to: connection) }
            }
        }
    }

    @available(iOS, deprecated: 17.0)
    private static func applyLegacyOrientation(
        _ orientation: UIInterfaceOrientation, to connection: AVCaptureConnection
    ) {
        guard connection.isVideoOrientationSupported else { return }
        switch orientation {
        case .landscapeLeft: connection.videoOrientation = .landscapeLeft
        case .landscapeRight: connection.videoOrientation = .landscapeRight
        case .portraitUpsideDown: connection.videoOrientation = .portraitUpsideDown
        default: connection.videoOrientation = .portrait
        }
    }
}

extension HandTrackingBridge: AVCaptureVideoDataOutputSampleBufferDelegate {
    public func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let landmarker, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        // LIVE_STREAM rejects a timestamp that does not increase.
        let timestampMs = Int(CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds * 1000)
        guard timestampMs > lastTimestampMs else { return }
        lastTimestampMs = timestampMs

        pushPreview(pixelBuffer)
        guard let image = try? MPImage(pixelBuffer: pixelBuffer) else { return }
        try? landmarker.detectAsync(image: image, timestampInMilliseconds: timestampMs)
    }

    private func pushPreview(_ pixelBuffer: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        // No copy here: pushFrameData copies before the buffer unlocks.
        let data = Data(bytesNoCopy: base, count: bytesPerRow * height, deallocator: .none)
        tracker.pushFrameData(
            data: data,
            width: Int32(CVPixelBufferGetWidth(pixelBuffer)),
            height: Int32(height),
            bytesPerRow: Int32(bytesPerRow)
        )
    }
}

extension HandTrackingBridge: HandLandmarkerLiveStreamDelegate {
    public func handLandmarker(
        _ handLandmarker: HandLandmarker,
        didFinishDetection result: HandLandmarkerResult?,
        timestampInMilliseconds: Int,
        error: Error?
    ) {
        // Layout per HandLandmarkWire.kt: [numHands, per hand: handedness, 21 * xyz].
        let hands = min(result?.landmarks.count ?? 0, Self.maxHands)
        let wire = KotlinFloatArray(size: Int32(1 + hands * Self.floatsPerHand))
        wire.set(index: 0, value: Float(hands))
        for h in 0..<hands {
            guard let result else { break }
            let base = 1 + h * Self.floatsPerHand
            let name = h < result.handedness.count ? result.handedness[h].first?.categoryName : nil
            wire.set(index: Int32(base), value: name?.hasPrefix("R") == true ? 1 : 0)
            for (i, point) in result.landmarks[h].prefix(21).enumerated() {
                let off = Int32(base + 1 + i * 3)
                wire.set(index: off, value: point.x)
                wire.set(index: off + 1, value: point.y)
                wire.set(index: off + 2, value: point.z)
            }
        }
        tracker.pushLandmarks(data: wire, timestampMs: Int64(timestampInMilliseconds))
    }
}

#else

/// MediaPipe is a CocoaPod; without `pod install` the app builds with hand tracking dark.
@objc public final class HandTrackingBridge: NSObject {
    @objc public init(tracker: IosHandTracker) {
        super.init()
        tracker.setAvailable(available: false)
        NSLog("[HandTracking] MediaPipeTasksVision not linked; run pod install")
    }

    @objc public func start() {}
    @objc public func stop() {}
}

#endif
