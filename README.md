# Camera Plugin for NativePHP Mobile

Camera plugin for NativePHP Mobile providing photo capture, video recording, and gallery picker functionality.

## Overview

The Camera API provides access to the device's camera for taking photos, recording videos, and selecting media from the gallery.

## Installation

```shell
composer require nativephp/mobile-camera
```

Don't forget to register the plugin:

```shell
php artisan native:plugin:register nativephp/mobile-camera
```

## Usage

### PHP (Livewire/Blade)

```php
use Native\Mobile\Facades\Camera;

// Take a photo
Camera::getPhoto();

// Take a photo with processing options
Camera::getPhoto([
    'id' => 'profile-photo-1',
    'processing' => [
        'maxWidth' => 1440,
        'maxHeight' => 1440,
        'format' => 'jpeg',
        'quality' => 82,
        'normalizeOrientation' => true,
    ],
]);

// Record a video
Camera::recordVideo();

// Record with max duration
Camera::recordVideo(['maxDuration' => 30]);

// Using fluent API
Camera::recordVideo()
    ->maxDuration(60)
    ->id('my-video-123')
    ->start();

// Pick images from gallery
Camera::pickImages('images', false);  // Single image
Camera::pickImages('images', true);   // Multiple images
Camera::pickImages('all', true);      // Any media type

// Pick media with image processing options
Camera::pickMedia([
    'mediaType' => 'all',
    'multiple' => true,
    'maxItems' => 5,
    'id' => 'gallery-import-1',
    'processing' => [
        'maxWidth' => 1920,
        'maxHeight' => 1920,
        'format' => 'webp',
        'quality' => 85,
        'normalizeOrientation' => true,
    ],
]);
```

### JavaScript (Vue/React/Inertia)

```js
import { Camera, On, Off, Events } from "#nativephp";

// Take a photo
await Camera.getPhoto();

// Take a photo with processing options
await Camera.getPhoto({
  id: "avatar-1",
  processing: {
    maxWidth: 1440,
    maxHeight: 1440,
    format: "jpeg",
    quality: 82,
    normalizeOrientation: true,
  },
});

// With identifier for tracking
await Camera.getPhoto().id("profile-pic");

// Record video
await Camera.recordVideo().maxDuration(60);

// Pick images
await Camera.pickImages().images().multiple().maxItems(5);

// Pick media with processing options
await Camera.pickMedia({
  mediaType: "all",
  multiple: true,
  maxItems: 5,
  id: "gallery-1",
  processing: {
    maxWidth: 1920,
    maxHeight: 1920,
    format: "webp",
    quality: 85,
    normalizeOrientation: true,
  },
});
```

## Processing Options

`Camera.GetPhoto` and `Camera.PickMedia` accept an optional `processing` object.

| Option                 | Type     | Default       | Description                      |
| ---------------------- | -------- | ------------- | -------------------------------- |
| `maxWidth`             | `int`    | source width  | Maximum output width             |
| `maxHeight`            | `int`    | source height | Maximum output height            |
| `format`               | `string` | `jpeg`        | Requested output format          |
| `quality`              | `int`    | `85`          | Compression quality (`1..100`)   |
| `normalizeOrientation` | `bool`   | `true`        | Normalize EXIF/image orientation |

### Format support

- Android: `jpeg`, `png`, `webp`
- iOS: `jpeg`, `png`, `webp` (if native WebP encode is unavailable at runtime, it falls back to JPEG)

## Events

### `PhotoTaken`

Fired when a photo is taken with the camera.

**Payload:**

- `string $path` - Output path for the processed image
- `?string $sourcePath` - Original source image path when available
- `string $mimeType` - Output MIME type (`image/jpeg`, `image/png`, `image/webp`)
- `string $extension` - Output extension (`jpg`, `png`, `webp`)
- `string $type` - Always `image`
- `int $width` - Output width
- `int $height` - Output height
- `int $bytes` - Output file size in bytes
- `bool $processed` - Whether processing/transformation was applied
- `?string $id` - Optional identifier if set in request

#### PHP

```php
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Camera\PhotoTaken;

#[OnNative(PhotoTaken::class)]
public function handlePhotoTaken(string $path)
{
    // Process the captured photo
    $this->processPhoto($path);
}
```

#### Vue

```js
import { On, Off, Events } from "#nativephp";
import { ref, onMounted, onUnmounted } from "vue";

const photoPath = ref("");

const handlePhotoTaken = (payload) => {
  photoPath.value = payload.path;
  processPhoto(payload.path);
};

onMounted(() => {
  On(Events.Camera.PhotoTaken, handlePhotoTaken);
});

onUnmounted(() => {
  Off(Events.Camera.PhotoTaken, handlePhotoTaken);
});
```

### `VideoRecorded`

Fired when a video is successfully recorded.

**Payload:**

- `string $path` - File path to the recorded video
- `string $mimeType` - Video MIME type (default: `'video/mp4'`)
- `?string $id` - Optional identifier if set via `id()` method

### `VideoCancelled`

Fired when video recording is cancelled by the user.

### `MediaSelected`

Fired when media is selected from the gallery.

**Payload:**

- `bool $success` - True when at least one item was processed successfully
- `array $files` - Processed files (images include metadata below, video/audio pass through)
- `int $count` - Number of successful files in `files`
- `array $errors` - Per-item failures; always present
- `bool $cancelled` - Present on cancellation payloads
- `?string $id` - Optional identifier if set in request

Each image item in `files` includes:

- `path`, `sourcePath`, `mimeType`, `extension`, `type`, `width`, `height`, `bytes`, `processed`

Each per-item error in `errors` includes:

- `index` - Index of failed selection item
- `code` - Error code (for example: `gallery_processing_failed`)
- `message` - Human-readable failure reason

```php
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Gallery\MediaSelected;

#[OnNative(MediaSelected::class)]
public function handleMediaSelected($success, $files, $count)
{
    foreach ($files as $file) {
        $this->processMedia($file);
    }
}
```

## PendingVideoRecorder API

### `maxDuration(int $seconds)`

Set the maximum recording duration in seconds.

### `id(string $id)`

Set a unique identifier for this recording to correlate with events.

### `event(string $eventClass)`

Set a custom event class to dispatch when recording completes.

### `remember()`

Store the recorder's ID in the session for later retrieval.

### `start()`

Explicitly start the video recording.

## Storage Locations

**Photos:**

- **Android:** App cache directory, processed outputs under `{cache}/Processed/`
- **iOS:** Temporary directory, processed outputs under `{tmp}/Processed/`

**Videos:**

- **Android:** App cache directory at `{cache}/video_{timestamp}.mp4`
- **iOS:** Temporary directory at `{tmp}/captured_video_{timestamp}.mp4`

## Notes

- **Permissions:** You must enable the `camera` permission in `config/nativephp.php` to use camera features
- If permission is denied, a `PermissionDenied` event is dispatched
- Camera permission is required for photos, videos, AND QR/barcode scanning
- Default image format is JPEG unless `processing.format` is provided
- Gallery result payloads always include an `errors` array (empty when there are no failures)
