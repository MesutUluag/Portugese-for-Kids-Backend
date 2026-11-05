#!/usr/bin/env python3
"""
Simulates SemanticImageCache.java against candidates in seed-images/manifest.json.

Evaluates natural story prompts (such as those in non-showing-pictures-prompts.md)
to diagnose whether an image hit misses due to:
  1. Cache logic / vocabulary gap in SemanticImageCache.java (synonyms, domain stems, multi-word regex, boilerplate)
  2. True visual gap requiring new seed image creation.

Usage:
  python3 seed-images/simulate_cache.py [--input <path_to_prompts_file>] [--threshold 0.55]
"""

import sys
import os
import json
import re
import math
import unicodedata
import argparse
from pathlib import Path
from collections import Counter

BASE_DIR = Path(__file__).resolve().parent

def find_manifest_file() -> Path:
    candidates = [
        BASE_DIR / "manifest.json",
        BASE_DIR.parent / "seed-images" / "manifest.json",
        Path.cwd() / "seed-images" / "manifest.json"
    ]
    for c in candidates:
        if c.exists():
            return c
    return BASE_DIR / "manifest.json"

MANIFEST_FILE = find_manifest_file()

def find_default_input() -> Path:
    rel = Path(".agents/skills/gcs-image-seeding/non-showing-pictures-prompts.md")
    candidates = [
        Path.cwd() / rel,
        BASE_DIR.parent / rel,
        BASE_DIR.parent / "Portugese-for-Kids-Backend" / rel,
        BASE_DIR.parent.parent / "Portugese-for-Kids-Backend" / rel,
        Path("/Users/mesutuluag/Documents/codingProjects/practice-portuguese/Portugese-for-Kids-Backend") / rel
    ]
    for c in candidates:
        if c.exists():
            return c
    return candidates[0]

DEFAULT_INPUT = find_default_input()

STOP_WORDS = {
    "a", "an", "the", "and", "or", "of", "in", "on", "at", "to",
    "for", "with", "is", "are", "that", "this", "it", "its", "be",
    "by", "as", "from", "up", "was", "has", "have", "had", "not",
    "about", "after", "into", "over", "down", "through", "their",
    "them", "our", "your", "his", "her", "while", "towards", "toward",
    "under", "near", "very", "much", "some", "any", "more", "before", "here", "until", "out",
    "they", "he", "she", "him", "we", "us", "i", "me", "you"
}

BOILERPLATE_WORDS = {
    "colorful", "colourful", "cute", "kids", "illustration", "storybook", "art",
    "bright", "colors", "colours", "simple", "background", "no", "text",
    "friendly", "style", "cartoon", "digital", "painting", "render",
    "scene", "image", "photo", "picture",
    "against", "behind", "beside", "front", "side", "corner",
    "wall", "door", "tree", "outside", "inside", "nearby", "area",
    "sunny", "happy", "laughing", "excited", "cheerful", "joyful",
    "cozy", "traditional", "formal", "official", "original", "fresh", "new",
    "young", "little", "small", "big", "tall", "short",
    "wooden", "plastic", "round", "filled", "portuguese", "portugal", "lisbon", "city",
    "first", "second", "next", "last", "together", "alone", "two",
    "going", "starting", "beginning", "trying", "using",
    "carefully", "nervously", "quickly", "slowly", "narrow", "rear", "back",
    "empty", "free", "day", "time", "arrival", "spot", "space", "sign", "direction",
    "turn", "convenience", "uniform", "contactless", "successful", "purchase", "open", "distance",
    "permission", "finished", "finish", "nodding", "nod", "leaning", "lean", "word", "sentence",
    "room", "math", "away", "fetch", "gestur", "gesture", "gesturing", "taking", "symptom", "symptoms", "start"
}

KEEP_ER_OR_NOUNS = {
    "waiter", "counter", "dinner", "number", "water", "scooter",
    "mother", "father", "sister", "brother", "finger", "paper",
    "officer", "teller", "cashier", "driver", "customer", "shopkeeper", "passenger",
    "fishmonger", "receptionist", "attendant"
}

DOMAIN_STEMS = {
    "school", "playground", "whiteboard",
    "restaurant", "waiter",
    "bank", "multibanco", "atm",
    "hospital", "doct",
    "cafeteria", "nata", "terrace",
    "airport", "board", "checkpoint",
    "store", "stall", "aisle", "trolley", "shopkeeper",
    "bus",
    "pharmacy", "pharmacist",
    "petrol", "pump", "fuel",
    "motorway", "traffic", "toll", "park", "roundabout", "seatbelt",
    "immigra", "biometric", "visa"
}

SYNONYMS = {
    "kid": "child", "boy": "child", "girl": "child", "children": "child",
    "student": "school", "pupil": "school", "baby": "child", "friend": "child", "classmate": "child",
    "man": "person", "woman": "person", "lady": "person", "gentleman": "person",
    "people": "person", "adult": "person", "applicant": "person", "customer": "person",
    "patient": "person", "parent": "person", "parents": "person", "family": "person",
    "father": "person", "mother": "person",
    "physician": "doctor", "nurse": "doctor", "medic": "doctor",
    "shop": "store", "market": "store", "supermarket": "store",
    "clinic": "hospital", "cafe": "cafeteria", "pastelaria": "cafeteria",
    "gas": "petrol", "galp": "petrol", "gasoline": "fuel", "diesel": "fuel",
    "assistant": "attendant", "cashier": "attendant",
    "classroom": "school", "schoolwork": "school", "whiteboard": "school",
    "teacher": "school", "teachers": "school", "textbook": "book",
    "reception": "receptionist",
    "wine": "drink", "codfish": "cod",
    "driving": "drive", "drives": "drive", "drove": "drive",
    "steering": "drive", "steers": "drive", "steered": "drive",
    "navigating": "drive", "navigates": "drive", "navigated": "drive",
    "approaching": "drive", "approaches": "drive", "approached": "drive",
    "pulling": "drive", "pulls": "drive", "changing": "drive",
    "reversing": "park", "reverses": "park", "reversed": "park",
    "unbuckling": "check", "buckling": "check",
    "placing": "share", "sharing": "share", "telling": "talk",
    "eating": "eat", "eats": "eat", "ate": "eat",
    "drinking": "drink", "drinks": "drink", "drank": "drink",
    "ordering": "order", "orders": "order", "ordered": "order",
    "buying": "buy", "buys": "buy", "bought": "buy",
    "paying": "pay", "pays": "pay", "paid": "pay", "payment": "pay",
    "sitting": "sit", "sits": "sit", "sat": "sit",
    "standing": "stand", "stands": "stand", "stood": "stand",
    "walking": "walk", "walks": "walk", "walked": "walk",
    "smiling": "smile", "smiles": "smile", "smiled": "smile",
    "talking": "talk", "talks": "talk", "talked": "talk",
    "explaining": "talk", "explains": "talk", "explained": "talk",
    "speaking": "speak", "speaks": "speak", "spoke": "speak",
    "asking": "ask", "asks": "ask", "asked": "ask",
    "looking": "look", "looks": "look", "looked": "look",
    "holding": "hold", "holds": "hold", "held": "hold",
    "showing": "show", "shows": "show", "showed": "show",
    "leaving": "leave", "leaves": "leave", "left": "leave",
    "playing": "play", "plays": "play", "played": "play",
    "reading": "read", "reads": "read",
    "writing": "write", "writes": "write", "wrote": "write",
    "learning": "learn", "learns": "learn", "learned": "learn",
    "helping": "help", "helps": "help", "helped": "help",
    "working": "work", "works": "work", "worked": "work",
    "waiting": "wait", "waits": "wait", "waited": "wait",
    "arriving": "arrive", "arrives": "arrive", "arrived": "arrive",
    "entering": "enter", "enters": "enter", "entered": "enter",
    "bill": "check", "receipt": "check",
    "medicine": "medication", "medicines": "medication", "drug": "medication", "drugs": "medication",
    "ticket": "ticket", "tickets": "ticket",
    "luggage": "bag", "suitcase": "bag", "suitcases": "bag", "bags": "bag",
    "bus": "bus", "car": "car", "vehicle": "car", "automobile": "car",
    "road": "street", "avenue": "street",
    "water": "drink", "juice": "drink", "juices": "drink",
    "dispenser": "machine"
}


def is_consonant(c: str) -> bool:
    return c.isalpha() and c not in "aeiou"


def stem(word: str) -> str:
    n = len(word)
    if n <= 3:
        return word
    if n > 5 and word.endswith("ing"):
        base = word[:-3]
        if len(base) > 2 and base[-1] == base[-2] and is_consonant(base[-1]):
            return base[:-1]
        return base
    if n > 6 and (word.endswith("tion") or word.endswith("sion")):
        return word[:-4]
    if n > 6 and word.endswith("ment"):
        return word[:-4]
    if n > 5 and (word.endswith("er") or word.endswith("or")) and word not in KEEP_ER_OR_NOUNS:
        return word[:-2]
    if n > 4 and word.endswith("ed"):
        base = word[:-2]
        if len(base) > 2 and base[-1] == base[-2] and is_consonant(base[-1]):
            return base[:-1]
        return base
    if n > 4 and word.endswith("es") and not word.endswith("ses") and not word.endswith("zes"):
        return word[:-2]
    if n > 3 and word.endswith("s") and not word.endswith("ss") and not word.endswith("us") and not word.endswith("is"):
        return word[:-1]
    return word


def tokenise(text: str) -> list[str]:
    ascii_text = unicodedata.normalize("NFD", text.lower())
    ascii_text = "".join(c for c in ascii_text if unicodedata.category(c) != "Mn")
    ascii_text = re.sub(r"\bbank\s+cards?\b", "card", ascii_text)
    ascii_text = re.sub(r"\bshopping\s+bags?\b", "bag", ascii_text)
    ascii_text = re.sub(r"\bservice\s+(?:station|area)s?\b", "petrol station", ascii_text)
    ascii_text = re.sub(r"\b(?:convenience|petrol|gas|galp)\s+station\s+(?:convenience\s+)?(?:shop|store)s?\b", "petrol counter", ascii_text)
    ascii_text = re.sub(r"\b(?:convenience|petrol|gas|galp)\s+(?:shop|store)s?\b", "petrol counter", ascii_text)
    ascii_text = re.sub(r"\bpencil\s+cases?\b", "pencil", ascii_text)
    ascii_text = re.sub(r"\bsigns?\s+showing\b", "sign", ascii_text)
    ascii_text = re.sub(r"\bmedical\s+(?:office|room|center|centre)s?\b", "hospital", ascii_text)
    ascii_text = re.sub(r"\bdisplay\s+screen\b", "board", ascii_text)
    ascii_text = re.sub(r"\bdrink\s+order\b", "order", ascii_text)

    raw_tokens = re.split(r"[^a-z0-9]+", ascii_text)
    tokens = []
    for t in raw_tokens:
        if len(t) <= 1 or t in STOP_WORDS or t in BOILERPLATE_WORDS:
            continue
        canon = SYNONYMS.get(t, t)
        st = stem(canon)
        if len(st) <= 1 or st in STOP_WORDS or st in BOILERPLATE_WORDS:
            continue
        tokens.append(st)
    return tokens


def extract_domains(tf: dict[str, float]) -> set[str]:
    return {k for k in tf if k in DOMAIN_STEMS}


def to_tf(text: str) -> dict[str, float]:
    tokens = tokenise(text)
    if not tokens:
        return {}
    counts = Counter()
    total_w = 0.0
    for t in tokens:
        w = 2.0 if t in DOMAIN_STEMS else 1.0
        counts[t] += w
        total_w += w
    return {k: v / total_w for k, v in counts.items()}


def compute_idf(doc_freq: dict[str, int], total_docs: int, term: str) -> float:
    min_df_floor = 2 if total_docs >= 10 else 0
    docs_with_term = max(doc_freq.get(term, 0), min_df_floor)
    return math.log(1.0 + float(total_docs) / (1.0 + docs_with_term))


def l2_norm(vec: dict[str, float]) -> dict[str, float]:
    norm_sq = sum(v * v for v in vec.values())
    if norm_sq == 0:
        return {}
    inv = 1.0 / math.sqrt(norm_sq)
    return {k: v * inv for k, v in vec.items()}


def cosine_sim(v1: dict[str, float], v2: dict[str, float]) -> float:
    dot = 0.0
    for k, val1 in v1.items():
        if k in v2:
            dot += val1 * v2[k]
    return min(1.0, max(0.0, dot))


def parse_input_file(path: Path) -> list[dict]:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    entries = []
    # Pattern 1: {"content": "{\"pt\":\"...\"}"}
    for match in re.finditer(r'\{\s*"content"\s*:\s*"(\{.*?\})"\s*\}', content, re.DOTALL):
        try:
            escaped = match.group(1).replace(r'\"', '"').replace(r'\\', '\\')
            inner = json.loads(escaped)
            entries.append(inner)
        except Exception:
            pass

    if not entries:
        # Pattern 2: direct JSON objects
        for match in re.finditer(r'\{[^{}]*"imagePrompt"[^{}]*\}', content):
            try:
                item = json.loads(match.group(0))
                entries.append(item)
            except Exception:
                pass

    if not entries:
        # Pattern 3: line by line prompts
        for line in content.splitlines():
            line = line.strip()
            if line and "illustration" in line:
                entries.append({"pt": line[:40], "imagePrompt": line})

    return entries


def run_simulation(input_path: Path, threshold: float = 0.55):
    if not MANIFEST_FILE.exists():
        print(f"Error: {MANIFEST_FILE} not found.")
        sys.exit(1)

    with open(MANIFEST_FILE, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    # Build corpus
    corpus = []
    doc_freq = Counter()
    for item in manifest:
        prompt = item["prompt"]
        tf = to_tf(prompt)
        domains = extract_domains(tf)
        corpus.append({
            "id": item.get("id", "custom"),
            "prompt": prompt,
            "tf": tf,
            "domains": domains
        })
        for term in tf:
            doc_freq[term] += 1

    total_docs = len(corpus) + 1

    # Load test inputs
    inputs = parse_input_file(input_path)
    if not inputs:
        print(f"No valid prompt entries found in {input_path}")
        sys.exit(1)

    print("=" * 80)
    print(f"SEMANTIC IMAGE CACHE SIMULATION (Threshold: {threshold:.2f}, Corpus: {len(corpus)} items)")
    print(f"Input: {input_path}")
    print("=" * 80)

    hits = 0
    misses = 0

    for idx, item in enumerate(inputs, 1):
        pt_text = item.get("pt", f"Prompt #{idx}")
        prompt = item.get("imagePrompt", "")
        if not prompt:
            continue

        q_tf = to_tf(prompt)
        q_domains = extract_domains(q_tf)
        q_raw_vec = {t: tf_val * compute_idf(doc_freq, total_docs, t) for t, tf_val in q_tf.items()}
        q_vec = l2_norm(q_raw_vec)

        scores = []
        for cand in corpus:
            c_domains = cand["domains"]
            if q_domains and c_domains and q_domains.isdisjoint(c_domains):
                continue  # Cross-domain guard
            c_raw_vec = {t: tf_val * compute_idf(doc_freq, total_docs, t) for t, tf_val in cand["tf"].items()}
            c_vec = l2_norm(c_raw_vec)
            score = cosine_sim(q_vec, c_vec)
            scores.append((score, cand["id"], cand["prompt"]))

        scores.sort(key=lambda x: x[0], reverse=True)
        top3 = scores[:3]
        best_score, best_id, best_prompt = top3[0] if top3 else (0.0, "NONE", "None")

        is_hit = best_score >= threshold
        status = "[HIT] " if is_hit else "[MISS]"
        if is_hit:
            hits += 1
        else:
            misses += 1

        top3_str = ", ".join(f"{c_id}: {sc:.3f}" for sc, c_id, _ in top3)
        print(f"{status} P{idx:02d} ({best_score:.3f} -> {best_id}): \"{pt_text}\"")
        print(f"       Top 3: [{top3_str}]")
        if not is_hit:
            print(f"       Query tokens: {tokenise(prompt)}")
            print(f"       Query domains: {q_domains}")
            print(f"       -> Action required: update SemanticImageCache.java or add new seed image.")
        print()

    total = hits + misses
    pct = (hits / total * 100.0) if total else 0.0
    print("=" * 80)
    print(f"SUMMARY: Total: {total} | Hits: {hits} | Misses: {misses} | Hit Rate: {pct:.1f}%")
    print("=" * 80)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Simulate SemanticImageCache on prompt files.")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="Path to prompt input file")
    parser.add_argument("--threshold", type=float, default=0.55, help="Similarity threshold (default: 0.55)")
    args = parser.parse_args()

    run_simulation(args.input, args.threshold)
