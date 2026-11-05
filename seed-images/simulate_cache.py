#!/usr/bin/env python3
"""
Simulates SemanticImageCache.java against candidates in seed-images/manifest.json.

Dynamically parses STOP_WORDS, BOILERPLATE_WORDS, KEEP_ER_OR_NOUNS, DOMAIN_STEMS,
SYNONYMS, and tokenise() multi-word regex rules directly from SemanticImageCache.java
so that any edits to the Java class are immediately reflected in simulation runs.

Usage:
  python3 seed-images/simulate_cache.py [--input <path_to_prompts_file>] [--threshold 0.55]
"""

import sys
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
        Path.cwd() / "seed-images" / "manifest.json",
    ]
    for c in candidates:
        if c.exists():
            return c
    return BASE_DIR / "manifest.json"


def find_java_source() -> Path:
    rel = Path("src/main/java/com/mesutuluag/portugeseforkidsbackend/image/SemanticImageCache.java")
    candidates = [
        Path.cwd() / rel,
        Path.cwd() / "Portugese-for-Kids-Backend" / rel,
        BASE_DIR.parent / rel,
        BASE_DIR.parent / "Portugese-for-Kids-Backend" / rel,
        BASE_DIR.parent.parent / "Portugese-for-Kids-Backend" / rel,
    ]
    for c in candidates:
        if c.exists():
            return c
    raise FileNotFoundError(f"Could not locate SemanticImageCache.java (searched {candidates})")


def find_default_input() -> Path:
    rel = Path(".agents/skills/gcs-image-seeding/non-showing-pictures-prompts.md")
    candidates = [
        Path.cwd() / rel,
        BASE_DIR.parent / rel,
        BASE_DIR.parent / "Portugese-for-Kids-Backend" / rel,
        BASE_DIR.parent.parent / "Portugese-for-Kids-Backend" / rel,
    ]
    for c in candidates:
        if c.exists():
            return c
    return candidates[0]


MANIFEST_FILE = find_manifest_file()
JAVA_SOURCE = find_java_source()
DEFAULT_INPUT = find_default_input()


def load_rules_from_java(java_path: Path):
    text = java_path.read_text(encoding="utf-8")

    def extract_set(name: str) -> set[str]:
        m = re.search(rf"{name}\s*=\s*Set\.of\((.*?)\);", text, re.DOTALL)
        if not m:
            raise ValueError(f"Could not parse {name} from {java_path}")
        return set(re.findall(r'"([^"]+)"', m.group(1)))

    stop_words = extract_set("STOP_WORDS")
    boilerplate_words = extract_set("BOILERPLATE_WORDS")
    keep_er_or_nouns = extract_set("KEEP_ER_OR_NOUNS")
    domain_stems = extract_set("DOMAIN_STEMS")

    synonyms = {}
    for k, v in re.findall(r's\.put\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', text):
        synonyms[k] = v

    tok_m = re.search(
        r"static String\[\] tokenise\(String text\)\s*\{(.*?)return Arrays\.stream",
        text,
        re.DOTALL,
    )
    replaces = []
    if tok_m:
        raw_replaces = re.findall(
            r'\.replaceAll\(\s*"([^"]+)"\s*,\s*"([^"]*)"\s*\)', tok_m.group(1)
        )
        for pat, rep in raw_replaces:
            if pat != r"\\p{M}+":
                replaces.append((pat.replace("\\\\", "\\"), rep))

    thresh_m = re.search(r"DEFAULT_SIMILARITY_THRESHOLD\s*=\s*([0-9.]+)\s*;", text)
    default_threshold = float(thresh_m.group(1)) if thresh_m else 0.55

    return (
        stop_words,
        boilerplate_words,
        keep_er_or_nouns,
        domain_stems,
        synonyms,
        replaces,
        default_threshold,
    )


(
    STOP_WORDS,
    BOILERPLATE_WORDS,
    KEEP_ER_OR_NOUNS,
    DOMAIN_STEMS,
    SYNONYMS,
    MULTIWORD_REPLACES,
    JAVA_DEFAULT_THRESHOLD,
) = load_rules_from_java(JAVA_SOURCE)


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
    for pat, rep in MULTIWORD_REPLACES:
        ascii_text = re.sub(pat, rep, ascii_text)

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
            escaped = match.group(1).replace(r'\"', '"').replace(r"\\", "\\")
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


def run_simulation(input_path: Path, threshold: float):
    if not MANIFEST_FILE.exists():
        print(f"Error: {MANIFEST_FILE} not found.")
        sys.exit(1)

    with open(MANIFEST_FILE, "r", encoding="utf-8") as f:
        manifest = json.load(f)

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
            "domains": domains,
        })
        for term in tf:
            doc_freq[term] += 1

    total_docs = len(corpus) + 1

    inputs = parse_input_file(input_path)
    if not inputs:
        print(f"No valid prompt entries found in {input_path}")
        sys.exit(1)

    print("=" * 80)
    print(f"SEMANTIC IMAGE CACHE SIMULATION (Threshold: {threshold:.2f}, Corpus: {len(corpus)} items)")
    print(f"Rules loaded live from: {JAVA_SOURCE.name} "
          f"(domains={len(DOMAIN_STEMS)}, synonyms={len(SYNONYMS)}, boilerplate={len(BOILERPLATE_WORDS)})")
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
        blocked = []
        for cand in corpus:
            c_domains = cand["domains"]
            c_raw_vec = {t: tf_val * compute_idf(doc_freq, total_docs, t) for t, tf_val in cand["tf"].items()}
            c_vec = l2_norm(c_raw_vec)
            score = cosine_sim(q_vec, c_vec)

            if q_domains and c_domains and q_domains.isdisjoint(c_domains):
                blocked.append((score, cand["id"], c_domains, cand["prompt"]))
                continue  # Cross-domain guard
            scores.append((score, cand["id"], cand["prompt"]))

        scores.sort(key=lambda x: x[0], reverse=True)
        blocked.sort(key=lambda x: x[0], reverse=True)
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
            print(f"       Query prompt:  \"{prompt}\"")
            print(f"       Query tokens:  {tokenise(prompt)}")
            print(f"       Query domains: {sorted(q_domains)}")
            if blocked and blocked[0][0] > best_score:
                b_score, b_id, b_dom, _ = blocked[0]
                print(f"       Domain-blocked candidate: {b_id} (score={b_score:.3f}, cand_domains={sorted(b_dom)})")
            print(f"       -> Action required: update SemanticImageCache.java (Type A) or add new seed image (Type B).")
        print()

    total = hits + misses
    pct = (hits / total * 100.0) if total else 0.0
    print("=" * 80)
    print(f"SUMMARY: Total: {total} | Hits: {hits} | Misses: {misses} | Hit Rate: {pct:.1f}%")
    print("=" * 80)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Simulate SemanticImageCache on prompt files.")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="Path to prompt input file")
    parser.add_argument("--threshold", type=float, default=JAVA_DEFAULT_THRESHOLD, help="Similarity threshold")
    args = parser.parse_args()

    run_simulation(args.input, args.threshold)
