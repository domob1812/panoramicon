#!/bin/bash

# Script to create Android app icons from source PNG
# Uses ImageMagick convert to generate all required resolutions

set -e

SOURCE_ICON="icon.png"
APP_DIR="app/src/main/res"

echo "Creating Android app icons from $SOURCE_ICON"

# Check if source icon exists
if [ ! -f "$SOURCE_ICON" ]; then
    echo "Error: $SOURCE_ICON not found!"
    exit 1
fi

# Check if convert command exists
if ! command -v convert &> /dev/null; then
    echo "Error: ImageMagick convert command not found!"
    echo "Please install ImageMagick: sudo apt install imagemagick"
    exit 1
fi

# Create mipmap directories if they don't exist
echo "Creating directories..."
mkdir -p "$APP_DIR/mipmap-mdpi"
mkdir -p "$APP_DIR/mipmap-hdpi"
mkdir -p "$APP_DIR/mipmap-xhdpi"
mkdir -p "$APP_DIR/mipmap-xxhdpi"
mkdir -p "$APP_DIR/mipmap-xxxhdpi"

# Create adaptive icon foreground (using source icon, scaled to safe zone)
echo "Creating adaptive icon foreground..."
# Scale icon to 61% of target size (safe zone), then center in full canvas
convert "$SOURCE_ICON" -resize 66x66 -background transparent -gravity center -extent 108x108 "$APP_DIR/mipmap-mdpi/ic_launcher_foreground.png"
convert "$SOURCE_ICON" -resize 99x99 -background transparent -gravity center -extent 162x162 "$APP_DIR/mipmap-hdpi/ic_launcher_foreground.png"
convert "$SOURCE_ICON" -resize 132x132 -background transparent -gravity center -extent 216x216 "$APP_DIR/mipmap-xhdpi/ic_launcher_foreground.png"
convert "$SOURCE_ICON" -resize 198x198 -background transparent -gravity center -extent 324x324 "$APP_DIR/mipmap-xxhdpi/ic_launcher_foreground.png"
convert "$SOURCE_ICON" -resize 264x264 -background transparent -gravity center -extent 432x432 "$APP_DIR/mipmap-xxxhdpi/ic_launcher_foreground.png"

echo "All icons created successfully!"
echo "Generated files:"
echo "- Adaptive foreground: ic_launcher_foreground.png (all densities)"
echo "- Background: ic_launcher_background.xml (already in drawable/)"
