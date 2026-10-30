#!/usr/bin/env python3
"""
Reusable Image Processor for Portuguese for Kids Semantic Image Cache.

Supports 3 ways to specify prompts for incoming images:
1. Sidecar text file: Place `foo.jpg` and `foo.txt` (containing the prompt) in incoming/.
2. Story auto-scan / ID: Uses canonical prompts from `prompts.json` or `Portugese-for-Kids/src/data/story-system-prompt-*.md`
   (e.g., naming an image `M-01.jpg` or `school-01.jpg`).
3. CLI single-file mode:
   python3 process_images.py --image path/to/file.png --prompt "custom prompt here"
"""

import os
import sys
import glob
import re
import hashlib
import json
import argparse
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("PIL / Pillow not installed. Run: pip3 install Pillow")
    sys.exit(1)

TARGET_WIDTH = 768
TARGET_HEIGHT = 336
JPEG_QUALITY = 60

BASE_DIR = Path(__file__).resolve().parent
INCOMING_DIR = BASE_DIR / "incoming"
PROCESSED_DIR = BASE_DIR / "processed"
MANIFEST_PATH = BASE_DIR / "manifest.json"
PROMPTS_JSON_PATH = BASE_DIR / "prompts.json"
def find_story_dir():
    candidates = [
        BASE_DIR.parent / "src" / "main" / "resources" / "prompts",
        BASE_DIR.parent / "Portugese-for-Kids-Backend" / "src" / "main" / "resources" / "prompts"
    ]
    for c in candidates:
        if c.exists():
            return c
    return candidates[0]

STORY_DIR = find_story_dir()

# Default baseline mapping (M-01 through M-19)
DEFAULT_PROMPTS = {
    "M-01": "children sitting together at a school canteen table with food trays, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-02": "a child waving to get the waiter's attention at a sunny Portuguese restaurant table, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-03": "a family sitting in a hospital waiting room with plastic chairs and a number display board, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-04": "a child pointing to their throat while talking to a kind doctor in a bright Portuguese clinic, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-05": "a family waiting at an airport boarding gate with hand luggage and a departure screen, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-06": "a child with a parent looking at airport departure boards searching for their gate at a Portuguese airport, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-07": "a family walking down a bright supermarket aisle with a trolley, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-08": "a child pointing at apples at a Portuguese market stall asking the price to a friendly shopkeeper, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-09": "a family boarding a yellow bus and tapping their travel card on the validator near the driver, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-10": "a pharmacist handing a medicine box to a parent at a bright pharmacy counter under a green cross sign, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-11": "a family car parked at a Galp petrol station forecourt next to a fuel pump, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-12": "a family car stuck in heavy traffic on a Portuguese motorway with many cars queued, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-13": "a car passing through a Via Verde toll gantry on a Portuguese highway, colorful cute kids illustration, storybook art, bright colors, simple background, no text",
    "M-14": "a person sitting in a government office waiting room with numbered tickets and plastic chairs, colorful friendly illustration, storybook art, bright colors, simple background, no text",
    "M-15": "an adult at an immigration office counter handing over a visa application form, colorful friendly illustration, storybook art, bright colors, simple background, no text",
    "M-16": "an adult at a desk with a laptop and documents at a government office, colorful friendly illustration, storybook art, bright colors, simple background, no text",
    "M-17": "a parent at an official government service counter checking their appointment on their phone, colorful friendly illustration, storybook art, bright colors, simple background, no text",
    "M-18": "an adult handing a stack of bank statement papers over a counter at an immigration office, colorful friendly illustration, storybook art, bright colors, simple background, no text",
    "M-19": "an adult pointing to a bank statement showing a Portuguese bank account balance at an immigration office counter, colorful friendly illustration, storybook art, bright colors, simple background, no text",
}


def load_known_prompts():
    """Load known prompts from prompts.json and story markdown files."""
    prompts = dict(DEFAULT_PROMPTS)
    if PROMPTS_JSON_PATH.exists():
        try:
            with open(PROMPTS_JSON_PATH, "r", encoding="utf-8") as f:
                prompts.update(json.load(f))
        except Exception as e:
            print(f"Warning: could not read {PROMPTS_JSON_PATH}: {e}")

    # Also harvest story prompt files
    if STORY_DIR.exists():
        for md_file in glob.glob(str(STORY_DIR / "story-system-prompt-*.md")):
            theme = Path(md_file).stem.replace("story-system-prompt-", "")
            try:
                with open(md_file, "r", encoding="utf-8") as f:
                    content = f.read()
                matches = [p for p in re.findall(r'\"([^\"]*colorful[^\"]*no text)\"', content) if "<" not in p]
                for idx, p in enumerate(matches, 1):
                    prompts[f"{theme}-{idx:02d}"] = p
            except Exception:
                pass
    return prompts


def process_single(image_path: Path, prompt: str, item_id: str = "custom") -> dict:
    """Resize, flatten to white, and save as 768x336 60% JPEG."""
    prompt = prompt.strip()
    sha = hashlib.sha256(prompt.encode("utf-8")).hexdigest()

    try:
        img = Image.open(image_path).convert("RGBA")
    except Exception as e:
        print(f"Failed to open {image_path}: {e}")
        return None

    # Flatten onto white background
    bg = Image.new("RGB", img.size, (255, 255, 255))
    bg.paste(img, mask=img.split()[3])
    bg = bg.resize((TARGET_WIDTH, TARGET_HEIGHT), Image.Resampling.LANCZOS)

    out_dir = PROCESSED_DIR / sha
    out_dir.mkdir(parents=True, exist_ok=True)

    jpeg_path = out_dir / "image.jpg"
    txt_path = out_dir / "prompt.txt"

    bg.save(jpeg_path, "JPEG", quality=JPEG_QUALITY)
    with open(txt_path, "w", encoding="utf-8") as f:
        f.write(prompt)

    return {
        "id": item_id,
        "filename": image_path.name,
        "sha256": sha,
        "prompt": prompt,
        "destination_blob_image": f"image-cache/{sha}/image.jpg",
        "destination_blob_prompt": f"image-cache/{sha}/prompt.txt",
    }


def process_batch():
    """Scan incoming/ directory and process all valid images."""
    INCOMING_DIR.mkdir(parents=True, exist_ok=True)
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)

    known_prompts = load_known_prompts()
    manifest = []
    
    # Load existing manifest if present to preserve history
    existing_manifest = {}
    if MANIFEST_PATH.exists():
        try:
            with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
                for entry in json.load(f):
                    existing_manifest[entry["sha256"]] = entry
        except Exception:
            pass

    valid_exts = {".jpg", ".jpeg", ".png", ".webp"}
    files = [f for f in INCOMING_DIR.iterdir() if f.is_file() and f.suffix.lower() in valid_exts and not f.name.startswith(".")]

    if not files:
        print(f"No valid image files found in {INCOMING_DIR}.")
        print("Tip: Drop images like 'M-01.jpg' or pairs like 'hero.png' + 'hero.txt' into that folder.")
        return

    for img_path in sorted(files):
        stem = img_path.stem
        prompt = None

        # Check 1: Sidecar file (.txt next to image)
        sidecar_txt = img_path.with_suffix(".txt")
        if sidecar_txt.exists():
            prompt = sidecar_txt.read_text(encoding="utf-8").strip()
            item_id = stem
        # Check 2: Known prompts dictionary
        elif stem.upper() in known_prompts:
            prompt = known_prompts[stem.upper()]
            item_id = stem.upper()
        elif stem in known_prompts:
            prompt = known_prompts[stem]
            item_id = stem
        else:
            print(f"Skipping {img_path.name}: No prompt found. Either create '{stem}.txt' or name it with a known prompt ID.")
            continue

        entry = process_single(img_path, prompt, item_id)
        if entry:
            existing_manifest[entry["sha256"]] = entry
            print(f"✓ Processed {img_path.name} ({item_id}) -> {entry['sha256'][:8]}...")

    manifest = list(existing_manifest.values())
    with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print(f"\nManifest updated at {MANIFEST_PATH} with {len(manifest)} total cached entries.")


def main():
    parser = argparse.ArgumentParser(description="Process images for GCS image cache")
    parser.add_argument("--image", help="Single image path to process")
    parser.add_argument("--prompt", help="Prompt text for single image")
    parser.add_argument("--id", default="custom", help="Identifier for single image")
    args = parser.parse_args()

    if args.image and args.prompt:
        entry = process_single(Path(args.image), args.prompt, args.id)
        if entry:
            print(f"Successfully processed single image -> {entry['sha256']}")
            print(json.dumps(entry, indent=2))
    else:
        process_batch()


if __name__ == "__main__":
    main()
