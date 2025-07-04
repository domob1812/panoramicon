# Panoramicon

A simple Android app for viewing spherical panoramic images using the
[PanoramaGL library](https://github.com/hannesa2/panoramagl).

## Features

- View 360° spherical panoramic images
- Touch navigation (pan, zoom)
- Gyroscope/accelerometer support for motion-based navigation
- Intent handling for opening/sharing images from other apps
- Fullscreen immersive viewing experience

## Usage

### Opening from other apps
The app registers to handle image viewing intents, so you can:
1. Share an image to Panoramicon from any gallery or file manager
2. Choose "Open with Panoramicon" when viewing images
3. Launch directly from file managers that support intent filtering

### Navigation
- **Touch**: Drag to pan around the panorama
- **Pinch**: Zoom in/out
- **Motion**: Tilt your device to look around (if accelerometer is enabled)

## Building

This is a standard Android project. To build:

```bash
gradle assembleDebug
```

## Dependencies

- PanoramaGL library (via JitPack)
- AndroidX libraries
- Kotlin

## Package

- Package name: `eu.domob.panoramicon`
- Supports Android API 21+ (Android 5.0+)

## Notes

The app assumes input images are spherical panoramas in
equirectangular projection (360° x 180°). Regular photos may display but
will not look correct in the viewer.
