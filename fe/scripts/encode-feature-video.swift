// Offline macOS encoder. Uses only the system AVFoundation framework.
// Input: consecutive BGRA frames on stdin. Output: silent, web-ready H.264 MP4.
import AVFoundation
import Foundation

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

guard CommandLine.arguments.count == 5,
      let width = Int(CommandLine.arguments[2]),
      let height = Int(CommandLine.arguments[3]),
      let fps = Int32(CommandLine.arguments[4]) else {
    fail("Usage: encode-feature-video output.mp4 width height fps")
}
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
writer.shouldOptimizeForNetworkUse = true
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: width,
    AVVideoHeightKey: height,
    AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: 2_400_000,
        AVVideoExpectedSourceFrameRateKey: fps,
        AVVideoMaxKeyFrameIntervalKey: fps * 2,
        AVVideoProfileLevelKey: AVVideoProfileLevelH264MainAutoLevel,
        AVVideoAllowFrameReorderingKey: false,
    ],
])
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,
    sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: width,
        kCVPixelBufferHeightKey as String: height,
        kCVPixelBufferCGImageCompatibilityKey as String: true,
        kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
    ])
guard writer.canAdd(input) else { fail("Cannot add video input") }
writer.add(input)
guard writer.startWriting() else { fail("Cannot start: \(String(describing: writer.error))") }
writer.startSession(atSourceTime: .zero)
let frameBytes = width * height * 4
var index: Int64 = 0
while true {
    var data = Data()
    while data.count < frameBytes {
        guard let chunk = try FileHandle.standardInput.read(upToCount: frameBytes - data.count),
              !chunk.isEmpty else { break }
        data.append(chunk)
    }
    if data.isEmpty { break }
    guard data.count == frameBytes else { fail("Incomplete frame \(index)") }
    while !input.isReadyForMoreMediaData {
        if writer.status == .failed { fail("Encode failed: \(String(describing: writer.error))") }
        Thread.sleep(forTimeInterval: 0.002)
    }
    autoreleasepool {
        var buffer: CVPixelBuffer?
        guard let pool = adaptor.pixelBufferPool,
              CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer) == kCVReturnSuccess,
              let pixelBuffer = buffer else { fail("Cannot allocate frame") }
        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        let stride = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let base = CVPixelBufferGetBaseAddress(pixelBuffer)!
        data.withUnsafeBytes { bytes in
            for row in 0..<height {
                memcpy(base.advanced(by: row * stride), bytes.baseAddress!.advanced(by: row * width * 4), width * 4)
            }
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
        guard adaptor.append(pixelBuffer, withPresentationTime: CMTime(value: index, timescale: fps)) else {
            fail("Cannot append frame: \(String(describing: writer.error))")
        }
    }
    index += 1
}
input.markAsFinished()
let finished = DispatchSemaphore(value: 0)
writer.finishWriting { finished.signal() }
finished.wait()
guard writer.status == .completed else { fail("Video incomplete: \(String(describing: writer.error))") }
print("\(url.lastPathComponent): \(index) frames, \(Double(index) / Double(fps))s")
