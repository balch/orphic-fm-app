#!/usr/bin/env python3
"""
Prepare ASL training data from Kaggle datasets for MediaPipe Model Maker.

Filters to the 19 classes needed by Orpheus, samples images per class,
and organizes into the directory structure expected by Model Maker.

Usage:
    python prepare_data.py \
        --alphabet_dir /path/to/asl_alphabet_train/asl_alphabet_train \
        --numbers_dir /path/to/Train_Nums \
        --output_dir ./data \
        --samples_per_class 200
"""
import argparse
import os
import random
import shutil
import struct

# Our 21 ASL classes
LETTER_CLASSES = ["A", "B", "C", "D", "H", "L", "M", "Q", "R", "S", "V", "W", "Y"]
NUMBER_CLASSES = ["1", "2", "3", "4", "5", "6", "7", "8"]
NONE_CLASS = "None"


def collect_images(src_dir, classes, samples_per_class, output_dir):
    """Copy a random sample of images from source class dirs to output."""
    for cls in classes:
        src = os.path.join(src_dir, cls)
        if not os.path.isdir(src):
            print(f"  WARNING: Class directory not found: {src}")
            continue

        dst = os.path.join(output_dir, cls)
        os.makedirs(dst, exist_ok=True)

        # List all image files
        images = [
            f for f in os.listdir(src)
            if f.lower().endswith((".jpg", ".jpeg", ".png", ".bmp"))
        ]

        if len(images) == 0:
            print(f"  WARNING: No images found in {src}")
            continue

        # Sample
        sample_size = min(samples_per_class, len(images))
        sampled = random.sample(images, sample_size)

        for img in sampled:
            shutil.copy2(os.path.join(src, img), os.path.join(dst, img))

        print(f"  {cls}: {sample_size} images (from {len(images)} available)")


def generate_none_class(output_dir, count):
    """Generate random noise JPEG images for the 'None' (no gesture) class.

    Uses raw BMP construction to avoid requiring PIL/Pillow.
    """
    dst = os.path.join(output_dir, NONE_CLASS)
    os.makedirs(dst, exist_ok=True)

    width, height = 200, 200
    for i in range(count):
        # Build a minimal 24-bit BMP in memory
        row_size = (width * 3 + 3) & ~3  # rows padded to 4-byte boundary
        pixel_data_size = row_size * height
        file_size = 54 + pixel_data_size  # 14 (file hdr) + 40 (DIB hdr) + pixels

        header = struct.pack(
            "<2sIHHI",  # BMP file header
            b"BM", file_size, 0, 0, 54,
        )
        dib = struct.pack(
            "<IiiHHIIiiII",  # BITMAPINFOHEADER
            40, width, height, 1, 24, 0, pixel_data_size, 2835, 2835, 0, 0,
        )
        pixels = bytearray(pixel_data_size)
        for y in range(height):
            for x in range(width):
                offset = y * row_size + x * 3
                pixels[offset] = random.randint(0, 255)      # B
                pixels[offset + 1] = random.randint(0, 255)  # G
                pixels[offset + 2] = random.randint(0, 255)  # R

        path = os.path.join(dst, f"none_{i:04d}.bmp")
        with open(path, "wb") as f:
            f.write(header)
            f.write(dib)
            f.write(bytes(pixels))

    print(f"  {NONE_CLASS}: {count} random noise images generated")


def main():
    parser = argparse.ArgumentParser(description="Prepare ASL training data")
    parser.add_argument(
        "--alphabet_dir",
        required=True,
        help="Path to alphabet training data (e.g., asl_alphabet_train/asl_alphabet_train)",
    )
    parser.add_argument(
        "--numbers_dir",
        required=True,
        help="Path to numbers training data (e.g., Train_Nums)",
    )
    parser.add_argument(
        "--output_dir",
        default="./data",
        help="Output directory for organized training data",
    )
    parser.add_argument(
        "--samples_per_class",
        type=int,
        default=200,
        help="Number of images to sample per class",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for reproducible sampling",
    )
    args = parser.parse_args()

    random.seed(args.seed)

    # Clean output directory
    if os.path.exists(args.output_dir):
        print(f"Removing existing output directory: {args.output_dir}")
        shutil.rmtree(args.output_dir)
    os.makedirs(args.output_dir)

    print(f"\nCollecting letter classes from: {args.alphabet_dir}")
    collect_images(args.alphabet_dir, LETTER_CLASSES, args.samples_per_class, args.output_dir)

    print(f"\nCollecting number classes from: {args.numbers_dir}")
    collect_images(args.numbers_dir, NUMBER_CLASSES, args.samples_per_class, args.output_dir)

    # Generate "None" class with random noise images (required by Model Maker)
    print(f"\nGenerating '{NONE_CLASS}' background class")
    generate_none_class(args.output_dir, args.samples_per_class)

    # Summary
    total = 0
    print("\n--- Summary ---")
    for cls in sorted(os.listdir(args.output_dir)):
        cls_dir = os.path.join(args.output_dir, cls)
        if os.path.isdir(cls_dir):
            count = len(os.listdir(cls_dir))
            total += count
            print(f"  {cls}: {count}")
    num_classes = len(LETTER_CLASSES) + len(NUMBER_CLASSES) + 1  # +1 for None
    print(f"\nTotal: {total} images across {num_classes} classes")
    print(f"Output: {os.path.abspath(args.output_dir)}")


if __name__ == "__main__":
    main()
