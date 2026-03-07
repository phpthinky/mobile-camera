import Foundation
import UIKit
import ImageIO
import UniformTypeIdentifiers

struct ImageProcessingOptions {
    let maxWidth: Int?
    let maxHeight: Int?
    let format: String
    let quality: Int
    let normalizeOrientation: Bool

    static func fromBridge(_ input: Any?) -> ImageProcessingOptions {
        let map = input as? [String: Any]

        let maxWidth = (map?["maxWidth"] as? NSNumber)?.intValue
        let maxHeight = (map?["maxHeight"] as? NSNumber)?.intValue
        let quality = min(max((map?["quality"] as? NSNumber)?.intValue ?? 85, 1), 100)

        let requestedFormat = (map?["format"] as? String)?.lowercased()
        let format: String
        switch requestedFormat {
        case "png":
            format = "png"
        case "webp":
            format = "webp"
        default:
            format = "jpeg"
        }

        return ImageProcessingOptions(
            maxWidth: maxWidth != nil && maxWidth! > 0 ? maxWidth : nil,
            maxHeight: maxHeight != nil && maxHeight! > 0 ? maxHeight : nil,
            format: format,
            quality: quality,
            normalizeOrientation: (map?["normalizeOrientation"] as? Bool) ?? true
        )
    }
}

private struct EncodedImage {
    let data: Data
    let actualFormat: String
}

struct ProcessedImageResult {
    let path: String
    let sourcePath: String?
    let mimeType: String
    let fileExtension: String
    let width: Int
    let height: Int
    let bytes: Int64
    let processed: Bool

    var payload: [String: Any] {
        var data: [String: Any] = [
            "path": path,
            "mimeType": mimeType,
            "extension": fileExtension,
            "type": "image",
            "width": width,
            "height": height,
            "bytes": bytes,
            "processed": processed
        ]
        if let sourcePath {
            data["sourcePath"] = sourcePath
        }
        return data
    }
}

enum IOSImageProcessor {
    static func process(image: UIImage, sourcePath: String?, options: ImageProcessingOptions, prefix: String) throws -> ProcessedImageResult {
        let normalized = options.normalizeOrientation ? normalizeOrientation(image) : image
        let sourceSize = normalized.size

        let target = targetSize(source: sourceSize, maxWidth: options.maxWidth, maxHeight: options.maxHeight)

        let finalImage: UIImage
        if Int(sourceSize.width.rounded()) != Int(target.width.rounded()) || Int(sourceSize.height.rounded()) != Int(target.height.rounded()) {
            finalImage = resize(image: normalized, targetSize: target)
        } else {
            finalImage = normalized
        }

        let encoded = try encode(image: finalImage, format: options.format, quality: options.quality)

        let fm = FileManager.default
        let outputDir = fm.temporaryDirectory.appendingPathComponent("Processed", isDirectory: true)
        if !fm.fileExists(atPath: outputDir.path) {
            try fm.createDirectory(at: outputDir, withIntermediateDirectories: true)
        }

        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        let ext = fileExtension(format: encoded.actualFormat)
        let outputURL = outputDir.appendingPathComponent("\(prefix)_\(timestamp).\(ext)")
        try encoded.data.write(to: outputURL, options: .atomic)

        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableUrl = outputURL
        try mutableUrl.setResourceValues(values)

        return ProcessedImageResult(
            path: outputURL.path(percentEncoded: false),
            sourcePath: sourcePath,
            mimeType: mimeType(format: encoded.actualFormat),
            fileExtension: ext,
            width: Int(finalImage.size.width.rounded()),
            height: Int(finalImage.size.height.rounded()),
            bytes: Int64(encoded.data.count),
            processed: Int(sourceSize.width.rounded()) != Int(target.width.rounded()) || Int(sourceSize.height.rounded()) != Int(target.height.rounded()) || sourcePath != nil
        )
    }

    static func process(fileURL: URL, options: ImageProcessingOptions, prefix: String) throws -> ProcessedImageResult {
        let sourcePath = fileURL.path(percentEncoded: false)

        let (image, orientationAlreadyNormalized) = try loadImageForProcessing(fileURL: fileURL, options: options)

        // If the CGImageSource thumbnail path already applied the EXIF transform,
        // skip the redundant normalizeOrientation pass.
        let adjustedOptions = orientationAlreadyNormalized && options.normalizeOrientation
            ? ImageProcessingOptions(maxWidth: options.maxWidth, maxHeight: options.maxHeight, format: options.format, quality: options.quality, normalizeOrientation: false)
            : options

        return try process(image: image, sourcePath: sourcePath, options: adjustedOptions, prefix: prefix)
    }

    private static func loadImageForProcessing(fileURL: URL, options: ImageProcessingOptions) throws -> (UIImage, orientationAlreadyNormalized: Bool) {
        guard let source = CGImageSourceCreateWithURL(fileURL as CFURL, nil) else {
            guard let fallback = UIImage(contentsOfFile: fileURL.path) else {
                throw NSError(domain: "Camera.ImageProcessing", code: 1001, userInfo: [NSLocalizedDescriptionKey: "Failed to load image file"])
            }
            return (fallback, false)
        }

        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let sourceWidth = (properties?[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue ?? 0
        let sourceHeight = (properties?[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue ?? 0

        guard sourceWidth > 0, sourceHeight > 0 else {
            guard let fallback = UIImage(contentsOfFile: fileURL.path) else {
                throw NSError(domain: "Camera.ImageProcessing", code: 1001, userInfo: [NSLocalizedDescriptionKey: "Failed to load image file"])
            }
            return (fallback, false)
        }

        let target = targetSize(
            source: CGSize(width: sourceWidth, height: sourceHeight),
            maxWidth: options.maxWidth,
            maxHeight: options.maxHeight
        )

        let maxPixelSize = max(1, Int(max(target.width, target.height).rounded()))
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]

        if let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) {
            return (UIImage(cgImage: cgImage), true)
        }

        guard let fallback = UIImage(contentsOfFile: fileURL.path) else {
            throw NSError(domain: "Camera.ImageProcessing", code: 1001, userInfo: [NSLocalizedDescriptionKey: "Failed to load image file"])
        }
        return (fallback, false)
    }

    private static func targetSize(source: CGSize, maxWidth: Int?, maxHeight: Int?) -> CGSize {
        let maxW = CGFloat(maxWidth ?? Int(source.width.rounded()))
        let maxH = CGFloat(maxHeight ?? Int(source.height.rounded()))

        if maxW <= 0 || maxH <= 0 {
            return source
        }

        // Never upscale. We only preserve or downscale.
        let scale = min(1.0, min(maxW / source.width, maxH / source.height))
        return CGSize(width: max(1.0, source.width * scale), height: max(1.0, source.height * scale))
    }

    private static func resize(image: UIImage, targetSize: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1

        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
    }

    private static func normalizeOrientation(_ image: UIImage) -> UIImage {
        if image.imageOrientation == .up {
            return image
        }

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = image.scale
        let renderer = UIGraphicsImageRenderer(size: image.size, format: format)

        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    private static func encode(image: UIImage, format: String, quality: Int) throws -> EncodedImage {
        switch format {
        case "png":
            if let data = image.pngData() {
                return EncodedImage(data: data, actualFormat: "png")
            }
        case "webp":
            if let data = encodeWebP(image: image, quality: quality) {
                return EncodedImage(data: data, actualFormat: "webp")
            }

            // Compatibility guard: if native WebP encoding is unavailable/fails,
            // gracefully fall back to JPEG instead of failing the request.
            if let fallback = image.jpegData(compressionQuality: CGFloat(quality) / 100.0) {
                return EncodedImage(data: fallback, actualFormat: "jpeg")
            }
        default:
            if let data = image.jpegData(compressionQuality: CGFloat(quality) / 100.0) {
                return EncodedImage(data: data, actualFormat: "jpeg")
            }
        }

        throw NSError(domain: "Camera.ImageProcessing", code: 1002, userInfo: [NSLocalizedDescriptionKey: "Failed to encode image"])
    }

    private static func encodeWebP(image: UIImage, quality: Int) -> Data? {
        guard let cgImage = image.cgImage else {
            return nil
        }

        let outputData = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(outputData, UTType.webP.identifier as CFString, 1, nil) else {
            return nil
        }

        let properties: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: CGFloat(quality) / 100.0
        ]

        CGImageDestinationAddImage(destination, cgImage, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else {
            return nil
        }

        return outputData as Data
    }

    private static func mimeType(format: String) -> String {
        switch format {
        case "png":
            return "image/png"
        case "webp":
            return "image/webp"
        default:
            return "image/jpeg"
        }
    }

    private static func fileExtension(format: String) -> String {
        switch format {
        case "png":
            return "png"
        case "webp":
            return "webp"
        default:
            return "jpg"
        }
    }
}
