#!/usr/bin/env python3
"""
Train a custom ASL gesture recognition model using MediaPipe Model Maker.

Usage:
    python train.py --data_dir ./data --output_dir ./output

Data directory structure:
    data/
        1/          # ASL number 1
            img001.jpg
            img002.jpg
        2/          # ASL number 2
            ...
        A/          # ASL letter A
            ...
        M/          # ASL letter M
            ...

Each subdirectory name becomes a gesture class label.
The model recognizes these classes and outputs them in the GestureRecognizer result.
"""
import argparse
import os
import sys
import types

# Shim: tensorflow_text has no macOS ARM wheels but mediapipe_model_maker's
# __init__.py eagerly imports text_classifier which needs it. We only use the
# gesture_recognizer module, so a fake module satisfies the import.
if "tensorflow_text" not in sys.modules:
    sys.modules["tensorflow_text"] = types.ModuleType("tensorflow_text")

from mediapipe_model_maker import gesture_recognizer


def main():
    parser = argparse.ArgumentParser(description="Train ASL gesture model")
    parser.add_argument("--data_dir", required=True, help="Path to training data directory")
    parser.add_argument("--output_dir", default="./output", help="Output directory for .task file")
    parser.add_argument("--epochs", type=int, default=30, help="Training epochs")
    parser.add_argument("--batch_size", type=int, default=32, help="Batch size")
    parser.add_argument("--lr", type=float, default=0.005, help="Learning rate")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)

    # Load dataset - Model Maker expects directory structure with subdirs per class
    data = gesture_recognizer.Dataset.from_folder(
        dirname=args.data_dir,
        hparams=gesture_recognizer.HandDataPreprocessingParams(),
    )

    # Split into train/validation/test
    train_data, rest_data = data.split(0.8)
    validation_data, test_data = rest_data.split(0.5)

    print(f"Train: {len(train_data)}, Validation: {len(validation_data)}, Test: {len(test_data)}")

    # Configure training hyperparameters
    hparams = gesture_recognizer.HParams(
        export_dir=args.output_dir,
        epochs=args.epochs,
        batch_size=args.batch_size,
        learning_rate=args.lr,
    )

    model_options = gesture_recognizer.ModelOptions()

    options = gesture_recognizer.GestureRecognizerOptions(
        model_options=model_options,
        hparams=hparams,
    )

    # Train
    model = gesture_recognizer.GestureRecognizer.create(
        train_data=train_data,
        validation_data=validation_data,
        options=options,
    )

    # Evaluate
    loss, accuracy = model.evaluate(test_data)
    print(f"Test loss: {loss:.4f}, Test accuracy: {accuracy:.4f}")

    # Export .task file (export_model prepends its own export_dir, so just pass filename)
    model.export_model("gesture_recognizer.task")
    output_path = os.path.join(args.output_dir, "gesture_recognizer.task")
    print(f"Model exported to: {output_path}")

    # Also copy to the project resources directory if it exists
    resource_path = os.path.join(
        os.path.dirname(__file__), "..", "..",
        "core", "mediapipe", "src", "jvmMain", "resources", "models",
        "gesture_recognizer.task",
    )
    resource_dir = os.path.dirname(resource_path)
    if os.path.isdir(resource_dir):
        import shutil
        shutil.copy2(output_path, resource_path)
        print(f"Copied to project resources: {resource_path}")


if __name__ == "__main__":
    main()
