# ASL Gesture Model Training Pipeline

> **Note (2026-02):** The native MediaPipe GestureRecognizer C API crashes in
> LIVE_STREAM async mode (upstream Eigen memory bug). ASL recognition currently
> uses a pure-Kotlin rule-based classifier (`AslSignClassifier.kt`) instead.
> This training pipeline is retained for potential future use — either via a
> TFLite-in-Kotlin inference path or if the upstream bug is fixed.

Trains a custom ASL gesture recognition model using MediaPipe Model Maker.
The resulting `gesture_recognizer.task` file is a bundle containing a gesture
embedder + custom classifier trained on our 20-sign ASL vocabulary.

## Quick Start

```bash
cd tools/train-asl-model

# 1. Set up Python environment (see "Environment Setup" below)
python3.11 -m venv .venv
source .venv/bin/activate
# ... install deps (see below)

# 2. Prepare training data from Kaggle datasets
python prepare_data.py \
    --alphabet_dir /path/to/asl_alphabet_train/asl_alphabet_train \
    --numbers_dir /path/to/Train_Nums \
    --output_dir ./data \
    --samples_per_class 200

# 3. Train the model
python train.py --data_dir ./data --output_dir ./output --epochs 30
```

The trained model is automatically copied to
`core/mediapipe/src/jvmMain/resources/models/gesture_recognizer.task`.

## Gesture Classes (22 total)

| Category | Classes | Synth Function |
|----------|---------|----------------|
| Numbers  | `1` `2` `3` `4` `5` `6` `7` `8` | Voice selection |
| Parameters | `B` `H` `L` `M` `S` `W` | Parameter selection (bend, hold, level, morph, sharpness, volume) |
| Modes | `D` `Q` | Layer prefix (duo, quad) |
| System | `C` `V` `Y` | Global params (coupling, vibrato, feedback) |
| Commands | `A` `R` | Deselect / clear, Remote adjust (no-gate pinch) |
| Background | `None` | No gesture detected |

## Step 1: Download Training Data

Download these two Kaggle datasets:

- **ASL Alphabet**: https://www.kaggle.com/datasets/grassknoted/asl-alphabet
  - Contains 3000 images per letter (A-Z) at 200x200px
- **ASL Numbers**: https://www.kaggle.com/datasets/lexset/synthetic-asl-numbers
  - Contains ~900 images per number (1-10)

Extract them to a convenient location, e.g.:

```
~/Source/asl-training/
    alphabet/asl_alphabet_train/asl_alphabet_train/
        A/   B/   C/   ...   Z/
    numbers/Train_Nums/
        1/   2/   3/   ...   10/
```

## Step 2: Environment Setup

**Requires Python 3.11.** Later versions lack compatible TensorFlow wheels.

```bash
# Install Python 3.11 if needed (macOS)
brew install python@3.11

# Create virtual environment
cd tools/train-asl-model
python3.11 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip 'setuptools<70' wheel
```

### Installing Dependencies (macOS ARM / Apple Silicon)

The `mediapipe-model-maker` package depends on `tensorflow-text`, which has
**no macOS ARM wheels**. The workaround is to install with `--no-deps` and
manually add the required packages:

```bash
# Core ML framework
pip install 'tensorflow==2.15.1'

# Model maker (skip broken dependency resolution)
pip install mediapipe-model-maker --no-deps

# Manually install model-maker's actual dependencies
pip install mediapipe==0.10.14 'tf_keras<2.16' tensorflow-hub tensorflow-datasets \
    lxml opencv-python-headless opencv-python scikit-learn

# Additional deps
pip install tensorflow-addons 'tensorflow-model-optimization<0.8.0'
pip install 'tf-models-official==2.15.0' --no-deps
pip install pandas py-cpuinfo pycocotools pyyaml gin-config sentencepiece sacrebleu

# Pin numpy/jax for TF 2.15 compatibility
pip install 'numpy<2.0.0,>=1.23.5' 'ml-dtypes~=0.3.1' 'jax==0.4.30' 'jaxlib==0.4.30'
```

> **Note:** `train.py` includes a shim that stubs out `tensorflow_text` at
> import time. This is safe because we only use the gesture recognizer trainer,
> not the text classifier.

### Installing Dependencies (Linux / x86_64)

On Linux, the standard install should work:

```bash
pip install -r requirements.txt
```

## Step 3: Prepare Training Data

`prepare_data.py` filters the Kaggle datasets to only the 20 ASL classes we
need and samples a fixed number of images per class:

```bash
python prepare_data.py \
    --alphabet_dir ~/Source/asl-training/alphabet/asl_alphabet_train/asl_alphabet_train \
    --numbers_dir ~/Source/asl-training/numbers/Train_Nums \
    --output_dir ./data \
    --samples_per_class 200 \
    --seed 42
```

This also creates a `None/` directory with 200 random noise images for the
background "no gesture" class, which MediaPipe Model Maker requires.

| Flag | Default | Description |
|------|---------|-------------|
| `--alphabet_dir` | (required) | Path to extracted ASL Alphabet dataset |
| `--numbers_dir` | (required) | Path to extracted ASL Numbers dataset |
| `--output_dir` | `./data` | Output directory for organized training data |
| `--samples_per_class` | `200` | Images to sample per class |
| `--seed` | `42` | Random seed for reproducible sampling |

After running, you should have 4400 images (200 x 22 classes) in `./data/`.

## Step 4: Train the Model

```bash
python train.py --data_dir ./data --output_dir ./output --epochs 30
```

| Flag | Default | Description |
|------|---------|-------------|
| `--data_dir` | (required) | Path to prepared training data |
| `--output_dir` | `./output` | Output directory for `.task` file |
| `--epochs` | `30` | Training epochs |
| `--batch_size` | `32` | Batch size |
| `--lr` | `0.005` | Learning rate |

Training takes ~2 minutes on Apple M4 Pro. The script:

1. Runs MediaPipe hand landmark detection on each image to extract embeddings
2. Splits data 80/10/10 into train/validation/test
3. Trains a small classifier head (3,092 params) on top of the hand embeddings
4. Evaluates on the test set (expect ~97% accuracy)
5. Exports `gesture_recognizer.task` to the output directory
6. Copies the model to `core/mediapipe/src/jvmMain/resources/models/` if it exists

## Retraining

To retrain with different data or hyperparameters, just re-run the steps.
The `gesture_recognizer.task` file in the project resources will be overwritten
automatically.
