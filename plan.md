# On-Device Document Assistant — Implementation Plan

> **Privacy-first mobile app** that indexes documents on-phone (photos, PDFs, screenshots) and lets users query them via voice/chat — entirely offline, zero cloud dependency.

---

## 📌 Analysis of the Document

### What You're Building
A **fully offline, on-device RAG (Retrieval-Augmented Generation)** system for Android that:
- Ingests documents (photos, screenshots, PDFs) from the device
- Runs OCR to extract text (multi-language/script)
- Classifies documents (passport, Aadhaar, PAN, SSN, driver's licence, etc.)
- Stores extracted data in an encrypted local database
- Accepts voice or typed queries and answers them using a local LLM
- **Never sends any data to the internet**

### Key Technical Constraints
| Constraint | Implication |
|---|---|
| Zero cloud | No Firebase, no remote APIs, no analytics SDKs that phone home |
| Multi-country | Passport MRZ (universal), national IDs (per-country regex), locale-aware date/number parsing |
| Multi-script OCR | ML Kit v2 covers Latin, Devanagari, Chinese, Japanese, Korean — separate bundles for others |
| Security-first | Encrypted DB (SQLCipher), biometric gate, no logs of sensitive fields, memory clearing |
| Real hardware | LLM latency varies wildly; budget phones with 4 GB RAM are the minimum bar |

---

## 🏗️ Platform Decision

> **Recommended: Native Kotlin (Android)**

| Factor | Native Kotlin | React Native |
|---|---|---|
| ML Kit integration | Native SDK, zero bridge overhead | Requires custom bridge |
| llama.cpp / MediaPipe | JNI / direct C++ — fastest path | llama.rn works but adds JS bridge latency |
| Room + SQLCipher | First-class support | Community wrapper, less maintained |
| BiometricPrompt | Native API | Bridge required |
| Camera / CameraX | Full control | Partial support |
| Dev speed | Slower UI build | Faster UI iteration |

**Verdict:** Because AI/ML is the *core* of this product (OCR + LLM + Biometric), native Kotlin gives the best performance, stability, and library support. Use Jetpack Compose for modern UI.

---

## 🔒 Security Architecture (Most Important)

> [!CAUTION]
> This is flagged as the **most important** concern. Every design decision below follows a "security-first" mindset.

### Threat Model
| Threat | Mitigation |
|---|---|
| Physical device theft | SQLCipher AES-256 encryption; biometric gate on app open |
| Root/ADB data extraction | Encrypted DB unreadable without key derived from Android Keystore |
| Memory scraping | Passphrase cleared from memory immediately after DB open |
| Network leakage | `NetworkSecurityConfig` blocks all network calls; verify with `StrictMode.ThreadPolicy` |
| App backup leakage | `android:allowBackup="false"` in manifest; exclude DB from backup |
| Screenshot/screen recording | `FLAG_SECURE` on all windows showing sensitive fields |
| Logcat leakage | No sensitive data in logs; ProGuard strips debug builds |

### Key Management Flow
```
First launch
    → Generate AES-256 key in Android Keystore (hardware-backed if available)
    → Key bound to biometric/device credential authentication
    → Key used to encrypt the SQLCipher passphrase
    → Encrypted passphrase stored in EncryptedSharedPreferences

App open
    → BiometricPrompt → on success → unlock Keystore key
    → Decrypt passphrase → open SQLCipher DB
    → Clear passphrase bytes from memory immediately
```

---

## 📦 Tech Stack (Final)

| Layer | Technology | Version / Notes |
|---|---|---|
| Language | Kotlin | 2.x + Coroutines |
| UI | Jetpack Compose | Material 3 |
| Camera | CameraX | `1.4.x` |
| OCR | ML Kit Text Recognition v2 | Bundled (~4 MB/script) |
| PDF | PdfRenderer (API 21+) + OCR fallback | |
| Document classification | Kotlin regex + keyword classifier | Custom per-country patterns |
| MRZ parsing | Custom ICAO 9303 parser + checksum | Based on JMRTD reference |
| Storage | Room 2.6+ + SQLCipher 4.19.0 | AES-256; key in Android Keystore; 16KB page size compliant |
| Encryption | Android Keystore + EncryptedSharedPreferences | No hardcoded keys |
| Biometric | BiometricPrompt API | Gates DB access + sensitive field display |
| Speech-to-text | Android SpeechRecognizer (offline, API 33+) | Vosk AAR fallback for API < 33 |
| Text-to-speech | Android TextToSpeech | Built-in |
| Local LLM | llama.cpp via JNI (Qwen3-1.7B Q4_K_M GGUF) | ~1.1 GB on disk |
| **Embedding model** | **all-MiniLM-L6-v2 via ONNX Runtime Android** | **~22 MB; 384-dim vectors; fully offline** |
| Vector search | Cosine similarity in Kotlin (float array math) | Hybrid with FTS5 BM25 for best retrieval |
| Network blocking | `NetworkSecurityConfig` (no cleartext, no domains) | Verified with StrictMode |
| Build | Gradle KTS + Version Catalogs | |

---

## 🗂️ Database Schema (Room)

```sql
-- Documents table
CREATE TABLE documents (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  file_uri   TEXT NOT NULL,           -- Original file URI (not the file itself)
  doc_type   TEXT,                    -- "PASSPORT", "AADHAR", "PAN", "SSN", "DRIVING_LICENSE", "UNKNOWN"
  country    TEXT,                    -- ISO 3166-1 alpha-2 ("IN", "US", "UK", ...)
  page_count INTEGER DEFAULT 1,       -- PDF page count; 1 for images
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

-- Structured fields table (sensitive — biometric-gated display)
CREATE TABLE document_fields (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  document_id INTEGER REFERENCES documents(id) ON DELETE CASCADE,
  field_name  TEXT NOT NULL,          -- "AADHAR_NUMBER", "PASSPORT_NO", "DOB", "EXPIRY", ...
  field_value TEXT NOT NULL,          -- Encrypted at DB level (SQLCipher)
  confidence  REAL                    -- OCR confidence 0.0–1.0
);

-- Chunks table: stores text segments + cosine-searchable embeddings
CREATE TABLE document_chunks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  document_id INTEGER REFERENCES documents(id) ON DELETE CASCADE,
  page_number INTEGER NOT NULL,       -- source page (useful for citations: "found on page 4")
  chunk_index INTEGER NOT NULL,       -- sequence within document
  chunk_text  TEXT NOT NULL,          -- ~400-token text segment
  token_count INTEGER,                -- approximate token count
  embedding   BLOB                    -- 384 × float32 = 1,536 bytes; all-MiniLM-L6-v2 vector
);

-- FTS5 on chunks (keyword/BM25 search — one half of hybrid retrieval)
CREATE VIRTUAL TABLE chunks_fts USING fts5(
  chunk_text, content='document_chunks', content_rowid='id'
);
```

> **Why store embeddings as BLOB?** 384 floats × 4 bytes = 1,536 bytes/chunk. 1,000 chunks ≈ 1.5 MB. Entirely manageable in SQLite. No separate vector DB needed.

---

## 📋 Phase-wise Implementation Plan

---

### 🔵 Phase 0 — Foundation & Project Setup
**Duration: ~3–5 days**

**Goal:** Running app on real device with correct permissions, security config, and project structure.

#### Tasks
- [ ] Create Android project (Kotlin + Jetpack Compose, `minSdk=26`, `targetSdk=36`, `compileSdk=36`)
- [ ] Set up Gradle KTS + Version Catalogs (`libs.versions.toml`)
- [ ] Add `NetworkSecurityConfig.xml` — block **all** domains (no `<domain>` entries, `cleartextTrafficPermitted="false"`)
- [ ] Add `android:allowBackup="false"` + `android:fullBackupContent="false"` to manifest
- [ ] Add `android:enableOnBackInvokedCallback="true"` to manifest (predictive back — required for targetSdk 36)
- [ ] Implement edge-to-edge UI: `WindowCompat.setDecorFitsSystemWindows(window, false)` — mandatory for targetSdk 36
- [ ] Add `FLAG_SECURE` to all Activity windows
- [ ] Set up `StrictMode` in debug builds to catch any accidental network calls
- [ ] Request runtime permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE` (legacy), `USE_BIOMETRIC`
- [ ] Set up Android Keystore key generation on first launch
- [ ] Set up `EncryptedSharedPreferences` for passphrase storage
- [ ] Scaffold main navigation with Compose NavHost (Home, Documents, Chat, Settings)
- [ ] Test on a real mid-range device — confirm zero network calls via proxy

#### Deliverable
> Blank app, no crashes, no network calls, biometric can be triggered.

---

### 🟢 Phase 1 — Document Ingestion
**Duration: ~4–5 days**

**Goal:** User can pick files. URIs are stored. No processing yet.

#### Tasks
- [ ] Build file/gallery picker using `ActivityResultContracts.GetMultipleContents`
- [ ] Support image types: `image/jpeg`, `image/png`, `image/webp`
- [ ] Support PDF: `application/pdf`
- [ ] Show thumbnail previews in a list (decode image to low-res bitmap for preview)
- [ ] Persist file URIs to Room DB (`documents` table, `doc_type=UNKNOWN`)
- [ ] Build "Documents" screen with Compose — list of ingested documents with status badge
- [ ] Handle permission denial gracefully with rationale dialog
- [ ] Handle URI persistence across reboots (take persistable URI permissions)

#### Deliverable
> User can pick photos and PDFs; they appear in a list.

---

### 🟡 Phase 2 — OCR + Text Extraction Pipeline
**Duration: ~10–14 days** *(extended — large PDF handling is non-trivial)*

**Goal:** Extract raw text from every ingested document safely, without memory crashes, even for 100+ page PDFs.

> [!WARNING]
> **Large PDF Problem:** A 100-page PDF rendered to bitmaps simultaneously = ~800 MB RAM → instant OOM crash on most Android devices. Also, 100 pages of text far exceeds any local LLM's context window (~8K tokens max). This phase solves both problems with streaming + chunking.

---

#### 2A — PDF Size Classification (decide processing strategy before starting)

```
PDF ingested
    ↓
Check page count (PdfRenderer.pageCount — no content loaded yet, instant)
    ↓
┌─ 1–2 pages   → ID/Passport/Card document → full OCR immediately
├─ 3–15 pages  → Short doc (bank statement, etc.) → full OCR in background
├─ 16–50 pages → Medium doc → full OCR via WorkManager (show progress)
└─ 51+ pages   → Large doc → show dialog:
                  "This PDF has X pages. Index:
                   [All pages — background, may take several minutes]
                   [First 20 pages only]
                   [Custom page range: __ to __]"
```

- [ ] Implement `PdfPageCountChecker` — reads page count with zero content loaded into memory
- [ ] Build `PdfIngestionStrategySelector` — returns strategy enum based on page count
- [ ] Build page-range selection bottom sheet UI for 50+ page PDFs

---

#### 2B — Streaming Page-by-Page Processing (fixes OOM)

> [!IMPORTANT]
> **Rule: Only 1 page bitmap lives in memory at a time.** Never accumulate bitmaps in a list.

```kotlin
// Correct pattern — streaming, memory-safe
PdfRenderer(fileDescriptor).use { renderer ->
    for (i in 0 until renderer.pageCount) {
        renderer.openPage(i).use { page ->
            val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, ARGB_8888)
            page.render(bitmap, null, null, RENDER_MODE_FOR_DISPLAY)
            val pageText = ocrProcessor.process(bitmap)   // OCR this page
            bitmap.recycle()                               // IMMEDIATELY free memory
            chunkAndStore(documentId, i, pageText)         // store chunks, not full text
            updateProgress(i, renderer.pageCount)          // notify WorkManager progress
        }
        // GC hint every 10 pages on low-RAM devices
        if (i % 10 == 0) System.gc()
    }
}
```

- [ ] Implement `PdfStreamProcessor` using above pattern (1 page in memory at a time)
- [ ] Auto-scale page render resolution: 150 DPI for OCR (not 300 DPI — wastes RAM, no OCR benefit)
- [ ] **Text-based PDF extraction — priority order (research-backed):**
  ```
  Priority 1: PdfRenderer native text API (API 35+)  — zero extra dependency, fastest
  Priority 2: androidx.pdf:pdf-viewer              — Google's lightweight 2025 AndroidX library
  Priority 3: PDFBox-Android                       — ONLY for files < 20 MB AND < 30 pages
  Priority 4: Render + ML Kit OCR fallback         — always works; stream-safe; slowest
  ```
  > ⚠️ PDFBox-Android loads entire doc into Java heap — 100-page PDF can spike 200–400 MB RAM on 4 GB devices. Use only as last resort with size guard.
- [ ] Auto-detect text-based vs scanned: try Priority 1/2 first; if empty string returned → fall back to Priority 4

---

#### 2C — Chunking Strategy (fixes LLM context window problem)

> [!IMPORTANT]
> **Do NOT store full PDF text as one giant string.** A 100-page document = ~60,000 tokens. The local LLM can only use ~4,000 at a time. Instead, split into chunks and retrieve only relevant ones at query time.

**Chunk size target: ~400 tokens (~300 words) with 50-token overlap**

```
Page 1 text (500 words)
    → Chunk 1: words 1–300   (page 1, chunk 0)
    → Chunk 2: words 251–550 (page 1–2, chunk 1)  ← 50-word overlap for context continuity
Page 2 text continues...
    → Chunk 3: words 501–800 (page 2–3, chunk 2)
    ...
```

**Updated DB Schema — see main schema section above (includes `embedding BLOB` column).**

- [ ] Implement `TextChunker` — splits text into ~400-token chunks with 50-token overlap
- [ ] Store chunks in `document_chunks` table (not in `documents.raw_text`)
- [ ] Build FTS5 virtual table on `chunk_text` for fast keyword search at query time
- [ ] Store `page_number` per chunk — useful for citing source: "found on page 4"

---

#### 2D — Image OCR (ML Kit v2)

- [ ] Add ML Kit Text Recognition v2 bundled dependencies:
  ```kotlin
  implementation("com.google.mlkit:text-recognition:16.0.1")             // Latin
  implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")  // Hindi
  implementation("com.google.mlkit:text-recognition-chinese:16.0.1")     // Chinese
  implementation("com.google.mlkit:text-recognition-japanese:16.0.1")    // Japanese
  implementation("com.google.mlkit:text-recognition-korean:16.0.1")      // Korean
  ```
- [ ] Build `OcrProcessor` — auto-detect script from first OCR pass, select correct recognizer
- [ ] Preprocess images: auto-rotate (EXIF), enhance contrast using `ColorMatrix`
- [ ] For single images: OCR → chunk → store (same chunk pipeline as PDFs)

---

#### 2E — WorkManager with Progress Tracking

- [ ] Wrap all PDF processing in `CoroutineWorker` (WorkManager)
- [ ] Emit `setProgress(workDataOf("page" to i, "total" to n))` each page
- [ ] Show live progress bar in Documents list: "Processing page 23 / 100"
- [ ] Handle `WorkManager` constraints: `requiresStorageNotLow()` — pause if storage critically low
- [ ] On cancellation: clean up partially processed chunks for that document
- [ ] Estimated time display: measure first 3 pages → extrapolate total time
- [ ] ⚠️ **WorkManager 10 KB payload limit:** NEVER pass OCR text, chunk text, or field values through `workDataOf()` — only pass `documentId` (Long), `pageNumber` (Int), `totalPages` (Int), `statusCode` (String). Store all text results directly in Room from within the Worker.

---

#### 2F — Memory safety rules

- [ ] Set `largeHeap="false"` in manifest — do NOT depend on large heap; stay within standard limits
- [ ] Enforce max bitmap size: if page dimensions exceed 2480×3508 px at 150 DPI, cap and warn
- [ ] On `OutOfMemoryError`: catch, mark document as `FAILED`, log page number that caused it
- [ ] Low-RAM device detection: `ActivityManager.isLowRamDevice()` → reduce render DPI to 100 on budget phones

---

#### 2G — Quality checks

- [ ] Test: single photo (Aadhaar, PAN, passport) — OCR in <3s
- [ ] Test: 5-page text PDF — extract via PDFBox in <1s (no OCR)
- [ ] Test: 10-page scanned PDF — stream+OCR, no OOM
- [ ] Test: 100-page scanned PDF on a 4 GB RAM budget device — must complete, not crash
- [ ] Verify chunks are correctly overlapping and page numbers are accurate
- [ ] Log OCR confidence per chunk; flag low-confidence chunks with a "low quality" badge

#### Deliverable
> Every selected document is chunked and indexed. A 100-page PDF processes without OOM. Progress is visible. Chunks are stored in FTS5-indexed table ready for RAG retrieval.

---

### 🟠 Phase 3 — Document Classification & Field Extraction
**Duration: ~10–14 days**

**Goal:** Classify each document and extract structured fields using deterministic regex — no LLM.

#### Tasks

**3A — Document Classifier**
- [ ] Build `DocumentClassifier` using keyword/pattern matching on `raw_text`:
  ```
  "REPUBLIC OF INDIA" + MRZ pattern → INDIAN_PASSPORT
  12-digit "XXXX XXXX XXXX" → AADHAR
  "INCOME TAX DEPARTMENT" + "[A-Z]{5}[0-9]{4}[A-Z]" → PAN
  "SSN" or "Social Security" + "XXX-XX-XXXX" → US_SSN
  "DRIVING LICENCE" + country keywords → DRIVING_LICENSE
  MRZ 2×44 chars → PASSPORT (universal)
  MRZ 3×30 chars → ID_CARD_TD1 (international)
  ```
- [ ] Support: `PASSPORT`, `AADHAR`, `PAN`, `SSN_US`, `NINO_UK`, `DRIVING_LICENSE_IN`, `DRIVING_LICENSE_US`, `DRIVING_LICENSE_UK`, `UNKNOWN`
- [ ] Build country detector: ISO keywords, language clues, currency symbols

**3B — MRZ Parser (Universal — highest priority)**
- [ ] Implement ICAO 9303 parser:
  - TD3 (Passport): 2 lines × 44 chars
  - TD1 (ID Card): 3 lines × 30 chars
- [ ] Implement check digit validation (weighted modulo-10)
- [ ] Extract fields: `surname`, `given_names`, `passport_number`, `nationality`, `dob`, `sex`, `expiry`, `personal_number`
- [ ] **Reject any field with a failed checksum** — never store corrupted data

**3C — Per-country field extractors**
- [ ] Aadhaar: `\d{4}\s?\d{4}\s?\d{4}` + name, DOB, gender
- [ ] PAN: `[A-Z]{5}[0-9]{4}[A-Z]`
- [ ] US SSN: `\d{3}-\d{2}-\d{4}` (flag: never store full SSN — store masked `XXX-XX-1234`)
- [ ] UK NINO: `[A-Z]{2}\d{6}[A-D]`
- [ ] Locale-aware date parsing: `DD/MM/YYYY`, `MM/DD/YYYY`, `YYYY-MM-DD`, etc.

**3D — Storage**
- [ ] Save structured fields to `document_fields` table
- [ ] Populate FTS5 index on `raw_text`
- [ ] Unit test: given known OCR text → verify correct fields extracted

#### Deliverable
> Each document is classified; structured fields visible (biometric-gated) in document detail screen.

---

### 🔴 Phase 4 — Encrypted Storage & Biometric Security
**Duration: ~5–7 days**

**Goal:** All data at rest is encrypted; sensitive fields are gated behind biometrics.

#### Tasks
- [ ] Integrate SQLCipher (modern library — EOL version deprecated 2023):
  ```kotlin
  // ✅ CORRECT — supports Android 15 mandatory 16KB page size
  implementation("net.zetetic:sqlcipher-android:4.19.0")
  implementation("androidx.sqlite:sqlite-ktx:2.4.0")
  ```
- [ ] Passphrase derived from Android Keystore key (hardware-backed on supported devices)
- [ ] `SupportOpenHelperFactory` (new API) wired into `Room.databaseBuilder`:
  ```kotlin
  val factory = SupportOpenHelperFactory(passphrase)
  Room.databaseBuilder(context, AppDatabase::class.java, "secure_app.db")
      .openHelperFactory(factory)
      .build()
  ```
- [ ] Clear passphrase byte array from memory immediately after DB open
- [ ] Implement `BiometricPrompt` gate using modern `registerForAuthenticationResult()` API:
  - Allow `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` to enable PIN/Pattern fallback if biometrics fail (crucial for accessibility and reliability).
  - App-open gate: required on cold start
  - Field-display gate: required before showing Aadhaar/SSN/passport number
  - Timeout: re-prompt after 5 minutes of background
- [ ] Add `FLAG_SECURE` — prevent screenshots on sensitive screens
- [ ] Test: pull DB file from device via ADB; verify it is not readable without key
- [ ] Test: uninstall app → reinstall → verify old data is inaccessible (key deleted from Keystore)

#### Deliverable
> DB is encrypted. App cannot be used without biometric. Sensitive numbers hidden behind second biometric tap.

---

### 🟣 Phase 5 — Voice & Chat Query Interface
**Duration: ~6–8 days**

**Goal:** User can ask questions by voice or text; app returns answers from structured fields.

#### Tasks

**5A — Chat UI**
- [ ] Build Compose chat screen: message bubbles (user / assistant), text input, mic button
- [ ] Input: typed text or voice (same processing pipeline)
- [ ] Output: text response + optional TTS readout

**5B — Speech-to-Text**
- [ ] Primary: `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (Android API 33+, offline)
- [ ] Fallback: Vosk AAR for older devices or non-English queries
  - Bundle small model (~50 MB) for primary language; download others on demand
- [ ] Handle permission: `RECORD_AUDIO`
- [ ] Show waveform animation while listening

**5C — Keyword Query Engine (no LLM)**
- [ ] Map query patterns to field lookups:
  ```
  "what is my aadhar" → document_fields WHERE field_name = 'AADHAR_NUMBER'
  "passport number" → field_name = 'PASSPORT_NO'
  "what is my SSN" → field_name = 'SSN_US'
  "when does my passport expire" → field_name = 'EXPIRY'
  "DOB" / "date of birth" → field_name = 'DOB'
  ```
- [ ] Support queries in Hindi, English (extensible to other languages)
- [ ] For matched queries: return structured field value directly (fast path — no LLM)
- [ ] For unmatched queries: FTS5 full-text search fallback → return matching document snippets

**5D — Text-to-Speech**
- [ ] Use `TextToSpeech` for reading out responses
- [ ] Detect response language and set TTS locale accordingly
- [ ] Add setting to toggle TTS on/off

**5E — Secure Export / Share**
- [ ] Allow users to share specific fields (e.g. masked Aadhaar number) via QR code generated offline.
- [ ] PDF Export: securely generate a watermarked or masked PDF of the document for safe sharing.
- [ ] Require biometric/PIN re-authentication immediately before granting the export.
- [ ] Provide an alternative to device screenshots (since `FLAG_SECURE` blocks them).

#### Deliverable
> User can ask "What's my Aadhaar number?" by voice and hear it read back. User can also securely export masked documents/QR codes. No LLM yet.

---

### 🟤 Phase 6 — Local LLM + RAG
**Duration: ~10–14 days**

**Goal:** Open-ended, multilingual queries answered by an on-device LLM with document context.

#### Tasks

**6A — Model acquisition**
- [ ] Model: **Qwen3-1.7B Q4_K_M GGUF** (~1.1 GB) from Hugging Face
- [ ] Download on first use (with progress bar + user consent), cache in app-internal storage
- [ ] Verify SHA-256 hash after download
- [ ] Never download over metered connection without explicit user approval

**6B — LLM integration via llama.cpp JNI + Foreground Service**
- [ ] Integrate `llama.cpp` as a native library (`.so`) via CMake / JNI (use official `examples/llama.android` as reference)
- [ ] Build `LlamaEngine` class: `loadModel()`, `generateResponse(context, query)`, `unloadModel()`
- [ ] **Run inference inside a Foreground Service** (`LlamaInferenceService`) — NOT a bare coroutine:
  ```kotlin
  class LlamaInferenceService : Service() {
      // startForeground() with "Thinking..." notification
      // Run LlamaEngine in coroutine; emit tokens via SharedFlow
      // stopSelf() on completion or cancellation
  }
  ```
  - Required permissions: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SHORT_TASK` (API 34+)
  - Reason: OS kills background processes on 4 GB devices; Foreground Service prevents mid-generation kills
- [ ] Batch token streaming across JNI boundary (don't emit 1 token at a time — batch 4–8 tokens per JNI call)
- [ ] Thermal throttling guard: if device reports `THERMAL_STATUS_SEVERE`, reduce context window size
- [ ] Implement context window management: trim older messages when approaching token limit

**6C — Embedding model (all-MiniLM-L6-v2 via ONNX Runtime)**
- [ ] Add ONNX Runtime Android dependency (updated to latest):
  ```kotlin
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
  ```
- [ ] Bundle `all-MiniLM-L6-v2.onnx` (~22 MB) in `assets/` — ships with the app, no download
- [ ] Build `EmbeddingEngine` class:
  ```kotlin
  class EmbeddingEngine(context: Context) {
      private val session: OrtSession  // loaded from assets
      
      // Returns FloatArray of size 384
      suspend fun embed(text: String): FloatArray {
          val tokens = tokenize(text)          // WordPiece tokenizer
          val output = session.run(tokens)     // ONNX inference
          return meanPool(output)              // mean-pool token embeddings → sentence vector
      }
  }
  ```
- [ ] Implement WordPiece tokenizer in Kotlin (or use pre-built vocab file bundled in assets)
- [ ] Embed each chunk during Phase 2 processing; store result in `document_chunks.embedding` BLOB
- [ ] Inference speed target: <50ms per chunk on mid-range device

---

**6D — Cosine Similarity (vector retrieval)**

```kotlin
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) {
        dot   += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    return dot / (sqrt(normA) * sqrt(normB))  // range: -1.0 to 1.0; higher = more similar
}
```

- [ ] Build `VectorRetriever`:
  - Load all chunk embeddings from DB into memory as `List<Pair<chunkId, FloatArray>>`
  - Embed query → compute cosine similarity against every chunk → rank by score
  - Return top-K chunk IDs
- [ ] Lazy-load embeddings: only load when LLM mode is active (save RAM)
- [ ] Memory estimate: 1,000 chunks × 384 floats × 4 bytes = ~1.5 MB in-memory — acceptable
- [ ] For 10,000+ chunks (very heavy users): process in batches of 500; return top-1 per batch → re-rank globally

---

**6E — Tiered Retrieval Cascade (Latency-Optimized)**

> [!TIP]
> **Key insight:** The LLM takes 3–25 seconds regardless. Cosine search adds at most 400ms — only 2% of total latency. BUT — running cosine on every single query wastes time when a simple keyword match works fine. So: **try the cheapest tier first, escalate only when needed.**

```
User query (voice or text)
    ↓
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🟢 TIER 1 — Keyword fast-path  (~2–5ms)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Exact match on document_fields table
("aadhar number", "passport no", "DOB", "SSN", "expiry" ...)
    ↓ HIT  →  return field value directly
              NO LLM CALL — instant response ✅
    ↓ MISS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🟡 TIER 2 — FTS5 BM25 search   (~10–80ms)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Full-text keyword search across all chunks
    ↓ STRONG HIT (top result BM25 score > threshold)
      → feed top-3 chunks to LLM → answer ✅
    ↓ WEAK / NO HIT (low confidence or 0 results)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔴 TIER 3 — Hybrid: FTS5 + Cosine (~200–500ms)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Only fires when Tier 2 wasn't confident enough
  ├─ Embed query via MiniLM ONNX (~20–50ms)
  ├─ FTS5 BM25 scores (already computed in Tier 2, reuse)
  └─ Cosine similarity against all chunk embeddings (~80–400ms)
       ↓
  final_score = (0.5 × bm25_normalized) + (0.5 × cosine_score)
       ↓
  Top-3 chunks by hybrid score → feed to LLM → answer ✅
    ↓ max cosine < 0.35 AND FTS5 returned nothing
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
⚫ TIER 4 — "I don't know"      (~0ms)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Skip LLM entirely — return canned response
"I don't have that information in your indexed documents."
Saves 3–25 seconds of LLM time on dead-end queries ✅
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Top-3 chunks (from Tier 2 or 3) → LLM prompt:
  [SYSTEM]: "Answer ONLY from the provided context.
             If not found, say 'I don't have that information'.
             Never invent sensitive data like ID numbers."
  [CONTEXT]: chunk_1 (📄 DocumentType, page X)
             chunk_2 (📄 DocumentType, page Y)
             chunk_3 ...
  [USER]:    original query
      ↓
  Qwen3-1.7B streams answer → chat UI
      ↓
  "📄 Source: Passport scan, page 1"
```

**Latency profile per query type:**

| Query | Tier hit | Retrieval time | LLM needed? | Total latency |
|---|---|---|---|---|
| *"my aadhaar number"* | Tier 1 | ~2ms | ❌ No | **~2ms** |
| *"passport expiry date"* | Tier 1 | ~3ms | ❌ No | **~3ms** |
| *"summarize my passport"* | Tier 2 | ~30ms | ✅ Yes | ~30ms + LLM |
| *"my tax identification"* | Tier 2 miss → Tier 3 | ~300ms | ✅ Yes | ~300ms + LLM |
| *"what did I eat for lunch"* | Tier 4 | ~0ms | ❌ No | **~0ms** |

- [ ] Implement `QueryRouter` — decides which tier to invoke, passes context down
- [ ] **Tier 1:** `FieldLookup` — regex + keyword match against `document_fields` table (~10 patterns per doc type)
- [ ] **Tier 2:** `FtsRetriever` — FTS5 `MATCH` query; define "strong hit" threshold (e.g. top result rank ≤ 3 AND at least 2 results)
- [ ] **Tier 3:** `HybridRetriever` — reuses FTS5 scores from Tier 2, adds cosine scores, merges with 0.5/0.5 weight
- [ ] BM25 normalization: map FTS5 rank position (1–N) to 0.0–1.0 (rank 1 = 1.0, rank N = 0.0)
- [ ] **Tier 4:** if max cosine < 0.35 AND FTS5 empty → skip LLM, return `NO_CONTEXT` response
- [ ] Source citation: append `📄 Source: [doc_type], page [page_number]` to every LLM response
- [ ] **System prompt hardened:** LLM told to never reveal data outside the provided context
- [ ] Log which tier was hit per query (debug builds only — no query content logged)

---

**6F — Performance Targets**

| Metric | Budget (4 GB) | Mid-range (8 GB) |
|---|---|---|
| Tier 1 response (no LLM) | <10ms | <5ms |
| Tier 2 FTS5 retrieval | <80ms | <30ms |
| Tier 3 cosine (1K chunks) | <400ms | <150ms |
| Query embedding (MiniLM) | <100ms | <50ms |
| LLM first token (TTFT) | <15s | <5s |
| LLM full response | <40s | <15s |

- [ ] Cosine is pre-cached in memory — do NOT reload from DB on every query
- [ ] Tier 3 runs cosine + FTS5 in parallel coroutines, not sequentially
- [ ] Show live tier indicator in debug UI: `[T1] [T2] [T3]` badge so you can see what fired
- [ ] Show streaming tokens as they generate — never make the user stare at a blank screen
- [ ] Add cancel button — essential for long LLM generations on budget devices

#### Deliverable
> Simple queries ("my Aadhaar number") return in milliseconds with no LLM. Fuzzy semantic queries ("my identity documents") escalate to cosine + LLM only when needed. Dead queries return instantly without wasting LLM time.

---

### ⚫ Phase 7 — Hardening, Testing & Polish
**Duration: ~7–10 days**

**Goal:** Production-ready, security-audited, tested on multiple devices.

#### Tasks

**7A — Security audit**
- [ ] Network audit: enable `StrictMode.VmPolicy` in release build; verify zero network calls in Charles/mitmproxy
- [ ] Memory audit: verify passphrase bytes are zeroed after DB open using memory profiler
- [ ] DB audit: extract DB file via ADB; attempt to open with SQLite browser — must fail
- [ ] Logcat audit: grep for any sensitive field values in logs
- [ ] Screen security: verify `FLAG_SECURE` prevents screenshots on all sensitive screens

**7B — Device testing matrix**

| Device | RAM | Chipset | Test focus |
|---|---|---|---|
| Budget (e.g., Redmi 12) | 4 GB | Snapdragon 4 Gen 1 | LLM latency, OOM handling |
| Mid-range (e.g., Pixel 7a) | 8 GB | Tensor G2 | OCR accuracy, biometric |
| High-end (e.g., Galaxy S24) | 12 GB | Snapdragon 8 Gen 3 | Full workflow speed |

- [ ] OCR accuracy: test 20 real documents across 5 document types
- [ ] LLM latency: measure TTFT (time to first token) per device class
- [ ] RAM: verify no OOM crash when LLM is loaded with 3+ documents indexed

**7C — UX polish**
- [ ] Onboarding flow: explain what the app does, what it stores, that nothing leaves the device
- [ ] Empty states: helpful guidance when no documents are indexed
- [ ] Error states: OCR failure, biometric failure, model download failure
- [ ] Accessibility: content descriptions, large text support, TalkBack compatibility
- [ ] Settings screen: clear all data, re-download LLM model, language preferences
- [ ] **Storage & Index Analytics UI**: build a transparent settings panel showing total documents, chunks, DB size, and model size to build trust and help manage device storage.

**7D — Final checklist**
- [ ] `android:allowBackup="false"` confirmed in manifest
- [ ] No third-party analytics SDK anywhere in the dependency tree
- [ ] `compileSdkVersion` and `targetSdkVersion` up to date
- [ ] ProGuard rules properly configured (no ML Kit / llama classes stripped)
- [ ] App signed with release keystore; keystore backed up securely

---

## 🗓️ Timeline Summary

| Phase | Focus | Est. Duration |
|---|---|---|
| **Phase 0** | Foundation, security config, project scaffold | 3–5 days |
| **Phase 1** | Document ingestion (file picker, URI storage) | 4–5 days |
| **Phase 2** | OCR + PDF streaming, chunking, embedding generation | 10–14 days |
| **Phase 3** | Document classification + field extraction | 10–14 days |
| **Phase 4** | Encrypted storage + biometrics | 5–7 days |
| **Phase 5** | Voice & chat query interface (no LLM) | 6–8 days |
| **Phase 6** | Local LLM + Hybrid RAG (FTS5 + Cosine Similarity) | 12–16 days |
| **Phase 7** | Hardening, testing, polish | 7–10 days |
| **Total** | | **~55–78 days** (solo) |

---

## ✅ MVP Scope (Subset of above, ~4 weeks)

If you want a working demo fast, build only:
1. Phase 0 (full — security cannot be skipped)
2. Phase 1 (full)
3. Phase 2A only (image OCR, skip PDF)
4. Phase 3B only (MRZ/passport — universal, easiest win)
5. Phase 4 (full — security cannot be skipped)
6. Phase 5C only (keyword query, no voice)

This gives you: **pick a passport photo → extract fields → query by text → encrypted at rest.** That's a solid, secure MVP.

---

## Open Questions

> [!IMPORTANT]
> **Platform:** Are you comfortable with native Kotlin + Jetpack Compose? If you prefer React Native (for JS familiarity), the plan can be adapted but some native modules will need custom JNI bridges.

> [!IMPORTANT]
> **LLM model download:** The Qwen3-1.7B GGUF is ~1.1 GB. Do you want to bundle it in the APK (large APK but offline-first) or download on first run (smaller APK, requires internet once)?

> [!IMPORTANT]
> **Target countries for v1:** Which document types are highest priority? Passport (universal) + Aadhaar/PAN (India) + SSN (US) covers a wide base. Should we start narrower?

> [!IMPORTANT]
> **Minimum Android version:** `minSdk=26` (Android 8) covers ~95% of active devices. Going lower complicates the Keystore and biometric APIs. Confirm this is acceptable.
