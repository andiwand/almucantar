@preconcurrency import AVFoundation
import os
import Sunradar
import SwiftUI

/// The preview of the back camera, and its camera model in the points of the view.
///
/// The focal length comes from the horizontal field of view of the active format (the long side
/// of the sensor). layerPointConverted(fromCaptureDevicePoint:) gives the crop, the scale, and
/// the rotation of the preview layer. iOS corrects the lens distortion of the video, so the model
/// has no distortion.
///
/// If `detectSun` is on, small frames go to SunDetector, and `onBlobs` gets the saturated regions
/// in the points of the view.
struct CameraView: UIViewRepresentable {
    var detectSun = false
    var onBlobs: ([ViewBlob]) -> Void = { _ in }
    let onCamera: (Sunradar.Camera?) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        update(view)
        view.start()
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) { update(view) }

    private func update(_ view: PreviewView) {
        view.onCamera = onCamera
        view.onBlobs = onBlobs
        view.detect.withLock { $0 = detectSun }
    }

    static func dismantleUIView(_ view: PreviewView, coordinator: ()) { view.stop() }

    final class PreviewView: UIView, AVCaptureVideoDataOutputSampleBufferDelegate {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        var onCamera: ((Sunradar.Camera?) -> Void)?
        var onBlobs: (([ViewBlob]) -> Void)?
        /// Read by the queue of the frames, so the detection switches on and off without a new
        /// configuration.
        let detect = OSAllocatedUnfairLock(initialState: false)
        /// Configuration and capture state belong to sessionQueue. The main thread only
        /// attaches this session to the preview layer.
        nonisolated(unsafe) private let session = AVCaptureSession()
        /// One serial queue for the configuration, the start, and the stop of the session, so a
        /// fast stop cannot come before the start.
        private let sessionQueue = DispatchQueue(label: "at.stefl.sunradar.session")
        /// Access only on sessionQueue. The permission reply can arrive after stop().
        nonisolated(unsafe) private var wantsRunning = false
        private let frames = DispatchQueue(label: "at.stefl.sunradar.frames")
        private var device: AVCaptureDevice?

        func start() {
            previewLayer.videoGravity = .resizeAspectFill
            previewLayer.session = session
            sessionQueue.async { self.wantsRunning = true }
            AVCaptureDevice.requestAccess(for: .video) { granted in
                guard granted else { return }
                self.sessionQueue.async {
                    guard self.wantsRunning else { return }
                    self.configure()
                }
            }
        }

        func stop() {
            sessionQueue.async {
                self.wantsRunning = false
                self.session.stopRunning()
            }
        }

        nonisolated private func configure() {
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                let input = try? AVCaptureDeviceInput(device: device)
            else { return }
            session.beginConfiguration()
            session.sessionPreset = .high
            if session.canAddInput(input) { session.addInput(input) }
            // The frames stay in the orientation of the sensor (no rotation of the connection),
            // so a point of the buffer is a capture device point after normalization.
            let output = AVCaptureVideoDataOutput()
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
            output.alwaysDiscardsLateVideoFrames = true
            output.setSampleBufferDelegate(self, queue: frames)
            if session.canAddOutput(output) { session.addOutput(output) }
            session.commitConfiguration()
            session.startRunning()
            DispatchQueue.main.async {
                self.device = device
                // The connection exists only now, so the first layout could not rotate it.
                self.updateConnectionRotation()
                self.updateCamera()
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            updateConnectionRotation()
            updateCamera()
        }

        private func updateConnectionRotation() {
            guard let connection = previewLayer.connection else { return }
            let orientation = window?.windowScene?.interfaceOrientation ?? .portrait
            let angle: CGFloat =
                switch orientation {
                case .landscapeRight: 0
                case .landscapeLeft: 180
                case .portraitUpsideDown: 270
                default: 90
                }
            if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
        }

        private func updateCamera() {
            guard let device, bounds.width > 0 else {
                onCamera?(nil)
                return
            }
            let fov = Double(device.activeFormat.videoFieldOfView) * .pi / 180
            // The focal length in units of the sensor width (the long side).
            let focal = 1 / (2 * tan(fov / 2))
            let center = previewLayer.layerPointConverted(fromCaptureDevicePoint: CGPoint(x: 0.5, y: 0.5))
            let side = previewLayer.layerPointConverted(fromCaptureDevicePoint: CGPoint(x: 0.6, y: 0.5))
            let pointsPerWidth = hypot(side.x - center.x, side.y - center.y) / 0.1
            let f = focal * Double(pointsPerWidth)
            onCamera?(
                Sunradar.Camera(
                    width: Double(bounds.width), height: Double(bounds.height), fx: f, fy: f, cx: Double(center.x),
                    cy: Double(center.y)))
        }

        nonisolated func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
            guard detect.withLock({ $0 }), let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
            CVPixelBufferLockBaseAddress(buffer, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
            guard let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) else { return }
            let width = CVPixelBufferGetWidthOfPlane(buffer, 0)
            let height = CVPixelBufferGetHeightOfPlane(buffer, 0)
            let stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
            let luma = UnsafeBufferPointer(start: base.assumingMemoryBound(to: UInt8.self), count: stride * height)
            // A step of 8 on a frame of about 1920 px gives the same grid as 4 on 640 px (Android).
            let blobs = SunDetector.findBlobs(luma, width: width, height: height, rowStride: stride, step: max(4, width / 240))
            let (w, h) = (Double(width), Double(height))
            DispatchQueue.main.async {
                let layer = self.previewLayer
                let view = blobs.map { blob -> ViewBlob in
                    let center = layer.layerPointConverted(
                        fromCaptureDevicePoint: CGPoint(x: blob.centerX / w, y: blob.centerY / h))
                    let edge = layer.layerPointConverted(
                        fromCaptureDevicePoint: CGPoint(x: (blob.centerX + blob.radius) / w, y: blob.centerY / h))
                    return ViewBlob(center: center, radius: hypot(edge.x - center.x, edge.y - center.y))
                }
                self.onBlobs?(view)
            }
        }
    }
}

/// A bright region of the camera image, in the points of the view.
struct ViewBlob: Equatable {
    var center: CGPoint
    var radius: CGFloat
}
