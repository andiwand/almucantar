import AVFoundation
import Sunradar
import SwiftUI

/// The preview of the back camera, and its camera model in the points of the view.
///
/// The focal length comes from the horizontal field of view of the active format (the long side
/// of the sensor). layerPointConverted(fromCaptureDevicePoint:) gives the crop, the scale, and
/// the rotation of the preview layer. iOS corrects the lens distortion of the video, so the model
/// has no distortion.
struct CameraView: UIViewRepresentable {
    let onCamera: (Sunradar.Camera?) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.onCamera = onCamera
        view.start()
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) { view.onCamera = onCamera }

    static func dismantleUIView(_ view: PreviewView, coordinator: ()) { view.stop() }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        var onCamera: ((Sunradar.Camera?) -> Void)?
        private let session = AVCaptureSession()
        private var device: AVCaptureDevice?

        func start() {
            previewLayer.videoGravity = .resizeAspectFill
            previewLayer.session = session
            AVCaptureDevice.requestAccess(for: .video) { granted in
                guard granted else { return }
                DispatchQueue.global(qos: .userInitiated).async { self.configure() }
            }
        }

        func stop() { session.stopRunning() }

        private func configure() {
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                let input = try? AVCaptureDeviceInput(device: device)
            else { return }
            session.beginConfiguration()
            session.sessionPreset = .high
            if session.canAddInput(input) { session.addInput(input) }
            session.commitConfiguration()
            session.startRunning()
            self.device = device
            DispatchQueue.main.async { self.updateCamera() }
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
    }
}
