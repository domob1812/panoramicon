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

## Example Images

If you're looking for example panoramic images to use with the app, Wikimedia Commons
has a large collection of freely licensed spherical panoramas available under
Creative Commons licenses. You can browse and download images from:

- [Spherical panoramas category](https://commons.wikimedia.org/wiki/Category:Spherical_panoramas) - A wide collection of panoramas from various contributors
- [Spherical panoramics by Domob](https://commons.wikimedia.org/wiki/Category:Spherical_panoramics_by_Domob) - Panoramas contributed by the app developer

Simply download any equirectangular panorama image and open it with Panoramicon
to explore it in full 360° view.

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
- Requires Android 13+ (API 33+)

## Notes

The app assumes input images are spherical panoramas in
equirectangular projection (360° x 180°). Regular photos may display but
will not look correct in the viewer.
