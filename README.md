# Camera Plugin for NativePHP Mobile

Camera plugin for NativePHP Mobile providing photo capture, video recording, and gallery picker functionality.

## Overview

The Camera API provides access to the device's camera for taking photos, recording videos, and selecting media from the gallery. Every event payload includes a `fileUri` (a `file://` URI suitable for use in `<img>` tags and web views) and supports an optional `includeBase64` flag that adds a data URI string to the payload — useful for canvas-based colour analysis, such as extracting a hex colour from a captured soil sample.

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
```

### JavaScript (Vue/React/Inertia)

#### Vue

```js
import { Camera, On, Off, Events } from '#nativephp';

// Take a photo
await Camera.getPhoto();

// With identifier for tracking
await Camera.getPhoto()
    .id('profile-pic');

// Record video
await Camera.recordVideo()
    .maxDuration(60);

// Pick images
await Camera.pickImages()
    .images()
    .multiple()
    .maxItems(5);
```

#### React / Inertia

In React, use `BridgeCall` to trigger the camera and `On`/`Off` inside `useEffect` to manage the event subscription:

```js
import { BridgeCall, On, Off, Events } from '#nativephp';
import { useEffect } from 'react';

const handlePhotoTaken = (payload) => {
    console.log(payload.fileUri);  // file:// URI for <img src>
    console.log(payload.base64);   // raw base64 string (if includeBase64: true)
};

useEffect(() => {
    On(Events.Camera.PhotoTaken, handlePhotoTaken);
    return () => {
        Off(Events.Camera.PhotoTaken, handlePhotoTaken);
    };
}, []);

const takePhoto = async () => {
    try {
        await BridgeCall('Camera.GetPhoto', {
            id: 'profile-pic',
            includeBase64: true,
        });
    } catch (e) {
        console.error('Camera failed', e);
    }
};
```

## Events

### `PhotoTaken`

Fired when a photo is taken with the camera.

**Payload:**
- `string $path` — Absolute file path to the captured photo
- `string $fileUri` — `file://` URI, ready for use in `<img src>` or a web view
- `string $mimeType` — Always `image/jpeg`
- `?string $id` — Optional identifier if set via `id()`
- `?string $base64` — Base64-encoded image data — only present when `includeBase64` is `true`. May arrive as a full data URI (`data:image/jpeg;base64,...`) or as a raw base64 string depending on platform; always normalise before use (see [Handling the base64 payload](#handling-the-base64-payload))

#### PHP

```php
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Camera\PhotoTaken;

#[OnNative(PhotoTaken::class)]
public function handlePhotoTaken(string $path, string $fileUri)
{
    // Use $path for server-side file operations
    // Use $fileUri to render a preview in a web view
    $this->processPhoto($path);
}
```

#### Vue

```js
import { On, Off, Events } from '#nativephp';
import { ref, onMounted, onUnmounted } from 'vue';

const photoPath = ref('');
const fileUri   = ref('');

const handlePhotoTaken = (payload) => {
    photoPath.value = payload.path;
    fileUri.value   = payload.fileUri; // use as <img :src="fileUri">
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
- `string $path` — Absolute file path to the recorded video
- `string $fileUri` — `file://` URI for use in `<video src>`
- `string $mimeType` — Video MIME type (default: `video/mp4`)
- `?string $id` — Optional identifier if set via `id()`
- `?string $base64` — Base64-encoded video data — only present when `includeBase64` is `true`. May arrive as a raw base64 string or a full data URI; normalise before use

### `VideoCancelled`

Fired when video recording is cancelled by the user.

### `MediaSelected`

Fired when media is selected from the gallery.

**Payload:**
- `bool $success`
- `int $count`
- `array $files` — Array of file objects, each containing:
  - `string path` — Absolute file path
  - `string fileUri` — `file://` URI for use in `<img>` / `<video>`
  - `string mimeType`
  - `string extension`
  - `string type` — `image` or `video`
  - `?string base64` — Base64-encoded data — only present when `includeBase64` is `true`. May be a raw base64 string or a full data URI; normalise before use
- `?string $id` — Optional identifier if set

```php
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Gallery\MediaSelected;

#[OnNative(MediaSelected::class)]
public function handleMediaSelected($success, $files, $count)
{
    foreach ($files as $file) {
        // $file['fileUri'] can be rendered in a web view immediately
        $this->processMedia($file);
    }
}
```

## Image Preview

Every payload includes a `fileUri` field that is a standard `file://` URI. Use it directly as an image source in your web view:

```html
<img :src="payload.fileUri" alt="Captured photo" />
```

```js
const handlePhotoTaken = (payload) => {
    document.getElementById('preview').src = payload.fileUri;
};
```

## Base64 / Colour Extraction

Pass `includeBase64: true` to receive a `base64` data URI alongside the file path. This is the recommended approach for canvas-based colour analysis (e.g. extracting a hex colour value from a soil sample photo) since it avoids any cross-origin or file-access restrictions on the canvas.

### PHP / Livewire

```php
Camera::getPhoto(['includeBase64' => true]);
```

### JavaScript

```js
await Camera.getPhoto()
    .id('soil-sample')
    .includeBase64(true);
```

### Handling the base64 payload

> **Important:** `payload.base64` may arrive as either a full data URI (`data:image/jpeg;base64,...`) or a raw base64 string (no prefix), depending on the platform. Always normalise it before use:

```js
const toDataUri = (base64) =>
    base64.startsWith('data') ? base64 : `data:image/jpeg;base64,${base64}`;
```

#### Image preview

```js
const handlePhotoTaken = (payload) => {
    if (!payload.base64) return;
    document.getElementById('preview').src = toDataUri(payload.base64);
};
```

#### Uploading to a server

Send the raw `payload.base64` string as JSON — no Blob or FormData needed:

```js
const handlePhotoTaken = async (payload) => {
    // Normalise for local preview
    setPreview(toDataUri(payload.base64));

    // Send raw base64 string to your API
    const response = await fetch('/api/upload', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        },
        body: JSON.stringify({
            image_base64: payload.base64, // raw string — let the server decode it
            name: 'photo.jpg',
        }),
    });

    const json = await response.json();
    console.log(json);
};
```

#### Canvas colour extraction

```js
const handlePhotoTaken = (payload) => {
    if (!payload.base64) return;

    const img = new Image();
    img.onload = () => {
        const canvas = document.createElement('canvas');
        canvas.width  = 1;
        canvas.height = 1;
        const ctx = canvas.getContext('2d');

        // Sample the centre pixel
        ctx.drawImage(img, Math.floor(img.width / 2), Math.floor(img.height / 2), 1, 1, 0, 0, 1, 1);
        const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
        const hex = '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0')).join('');
        console.log('Colour:', hex);
    };
    img.src = toDataUri(payload.base64);
};
```

> **Note:** `includeBase64` is opt-in and defaults to `false`. Omit it (or set it to `false`) for normal photo/video capture — the base64 string for a full-resolution image can be several megabytes and will noticeably increase event payload size.

## Gallery with base64

```js
await Camera.pickImages()
    .images()
    .includeBase64(true);

On(Events.Gallery.MediaSelected, (payload) => {
    payload.files.forEach((file) => {
        console.log(file.fileUri);   // file:// URI for <img src>
        console.log(file.base64);    // data URI for canvas
    });
});
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

### `includeBase64(bool $include)`

When `true`, the `VideoRecorded` event payload will include a `base64` data URI of the recorded video. Defaults to `false`.

### `start()`

Explicitly start the video recording.

## Storage Locations

**Photos:**
- **Android:** DCIM/Camera (visible in Gallery); falls back to app cache at `{filesDir}/captured_*.jpg`
- **iOS:** Application Support at `~/Library/Application Support/captured_photo_*.jpg`

**Videos:**
- **Android:** App cache directory at `{filesDir}/video_*.mp4`
- **iOS:** Application Support at `~/Library/Application Support/captured_video_*.mp4`

**Gallery picks:**
- **Android:** `{filesDir}/Gallery/gallery_selected_*.{ext}`
- **iOS:** `~/Library/Application Support/Gallery/gallery_selected_*.{ext}`

## Notes

- **Permissions:** You must enable the `camera` permission in `config/nativephp.php` to use camera features
- If permission is denied, camera functions will dispatch a `PermissionDenied` event
- Camera permission is required for photos, videos, AND QR/barcode scanning
- File formats: JPEG for photos, MP4 for videos
- `fileUri` is always included in every event payload at no extra cost
- `base64` is opt-in via `includeBase64: true` — avoid enabling it by default for large media files
