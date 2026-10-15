# On-Device Document Assistant
## Simple Phase-Wise Development Plan

This plan follows `plan.md` strictly. The product is a privacy-first Android app that indexes photos, screenshots, and PDFs on-device and answers voice or typed questions completely offline.

## Fixed Technical Direction

- Platform: Native Android with Kotlin 2.x and Jetpack Compose.
- Minimum SDK: 26. Target and compile SDK: 36.
- Storage: Room with SQLCipher AES-256 encryption.
- OCR: Bundled ML Kit Text Recognition v2.
- Local language model: Qwen3-1.7B Q4_K_M GGUF through llama.cpp JNI.
- Embeddings: all-MiniLM-L6-v2 through ONNX Runtime Android.
- Search: FTS5 BM25 plus cosine similarity.
- Security: Android Keystore, EncryptedSharedPreferences, BiometricPrompt, and FLAG_SECURE.
- Network: no cloud services, no analytics SDKs, and no network dependency.

## Phase 0: Foundation and Project Setup

**Duration:** 3-5 days

**Purpose:** Create a secure Android application shell that runs on a real device.

**Work:**
- Create the Kotlin, Compose, Gradle KTS, and Version Catalog project.
- Configure SDK levels, CameraX, Room, security libraries, and required dependencies.
- Block cleartext traffic and all network domains with NetworkSecurityConfig.
- Disable backups in the manifest.
- Enable predictive back and edge-to-edge UI.
- Apply FLAG_SECURE to activity windows.
- Add debug StrictMode checks for accidental network calls.
- Add image, video, legacy storage, and biometric permissions as required.
- Generate an Android Keystore key on first launch.
- Store the encrypted SQLCipher passphrase with EncryptedSharedPreferences.
- Create Compose navigation for Home, Documents, Chat, and Settings.
- Test on real hardware and confirm that no network calls occur.

**Deliverable:** A blank app with no crashes, no network calls, and a working biometric trigger.

## Phase 1: Document Ingestion

**Duration:** 4-5 days

**Purpose:** Let users select documents and store their references locally.

**Work:**
- Add multi-file selection with GetMultipleContents.
- Accept JPEG, PNG, WebP, and PDF files.
- Display low-resolution thumbnails.
- Store persistent file URIs in the `documents` table with `doc_type=UNKNOWN`.
- Build the Documents Compose screen with status badges.
- Handle denied permissions with a rationale dialog.
- Persist URI permissions across reboots.

**Deliverable:** Users can select photos and PDFs and see them in the Documents list.

## Phase 2: OCR, PDF Processing, and Indexing

**Duration:** 10-14 days

**Purpose:** Extract text safely, handle large PDFs without OOM crashes, and index chunks for search.

### 2A. PDF Strategy

- Read page count without loading page content.
- Use these strategies:
  - 1-2 pages: full OCR immediately.
  - 3-15 pages: full OCR in background.
  - 16-50 pages: WorkManager processing with progress.
  - 51+ pages: all pages, first 20 pages, or custom page range.
- Add the page-range selection bottom sheet for large PDFs.

### 2B. Streaming PDF Processing

- Process one page bitmap at a time.
- Render at 150 DPI, or 100 DPI on low-RAM devices.
- Recycle each bitmap immediately after OCR.
- Store chunks rather than full document text.
- Prefer text extraction in this order:
  1. PdfRenderer native text API on API 35+.
  2. `androidx.pdf:pdf-viewer`.
  3. PDFBox-Android only below 20 MB and 30 pages.
  4. Streaming render plus ML Kit OCR fallback.
- Fall back to OCR when text extraction returns no text.

### 2C. Chunking and FTS5

- Split text into about 400-token chunks with 50-token overlap.
- Store each chunk with document ID, page number, chunk index, text, token count, and embedding field.
- Add an FTS5 virtual table for BM25 keyword search.
- Preserve page numbers for source citations.

### 2D. Image OCR

- Add bundled ML Kit recognizers for Latin, Devanagari, Chinese, Japanese, and Korean.
- Detect the script and choose the matching recognizer.
- Auto-rotate using EXIF and improve contrast with ColorMatrix.
- Send image text through the same chunking and storage pipeline.

### 2E. Background Work

- Use CoroutineWorker for PDF processing.
- Report page and total-page progress.
- Require storage not to be critically low.
- Remove partial chunks when work is cancelled.
- Estimate remaining time from the first three pages.
- Pass only small identifiers and status values through WorkManager data. Store OCR text and fields directly in Room.

### 2F. Memory and Quality Rules

- Keep `largeHeap=false`.
- Cap page bitmaps at 2480x3508 pixels at 150 DPI.
- Mark the document `FAILED` on OOM and record the failing page.
- Test photos, text PDFs, scanned PDFs, and a 100-page scanned PDF on a 4 GB device.
- Verify chunk overlap, page numbers, and low-confidence badges.

**Deliverable:** Selected documents are chunked, FTS5-indexed, and processed with visible progress without OOM crashes.

## Phase 3: Classification and Field Extraction

**Duration:** 10-14 days

**Purpose:** Identify document types and extract structured fields without an LLM.

**Work:**
- Build keyword and regex classification for passports, Aadhaar, PAN, US SSN, UK NINO, and driving licences.
- Detect countries using ISO keywords, language clues, and currency symbols.
- Implement ICAO 9303 MRZ parsing for TD3 passports and TD1 ID cards.
- Validate MRZ check digits and reject failed fields.
- Extract surname, given names, passport number, nationality, date of birth, sex, expiry, and personal number.
- Add country-specific extractors for Aadhaar, PAN, US SSN, and UK NINO.
- Parse locale-specific date formats.
- Store fields in `document_fields`.
- Add unit tests using known OCR text.

**Deliverable:** Each document is classified and its fields are available in the document detail view.

## Phase 4: Encrypted Storage and Biometric Security

**Duration:** 5-7 days

**Purpose:** Protect all stored data and gate sensitive values.

**Work:**
- Integrate SQLCipher Android 4.19.0 with Room.
- Derive or protect the database passphrase through Android Keystore.
- Open Room with `SupportOpenHelperFactory`.
- Clear passphrase bytes immediately after opening the database.
- Require BiometricPrompt with strong biometrics or device credential fallback.
- Require authentication at cold start and before displaying sensitive fields.
- Re-prompt after five minutes in the background.
- Keep FLAG_SECURE enabled on sensitive screens.
- Verify that an extracted database cannot be opened without its key.
- Verify that uninstalling the app removes access to old data.

**Deliverable:** The database is encrypted, app access is biometric-gated, and sensitive numbers require a second authentication.

## Phase 5: Voice and Chat Query Interface

**Duration:** 6-8 days

**Purpose:** Allow users to ask questions by text or voice without an LLM.

**Work:**
- Build the Compose chat screen with user and assistant messages, text input, and microphone control.
- Add on-device SpeechRecognizer for API 33+.
- Use Vosk as the fallback for older devices or non-English queries.
- Request RECORD_AUDIO and show listening feedback.
- Map common queries to `document_fields` lookups.
- Support English and Hindi query patterns.
- Use FTS5 as a fallback for unmatched questions.
- Add TextToSpeech with response-language detection and an on/off setting.
- Add biometric-gated masked QR and PDF export.

**Deliverable:** A user can ask for a field such as an Aadhaar number by text or voice and receive a response without an LLM.

## Phase 6: Local LLM and Hybrid RAG

**Duration:** 12-16 days

**Purpose:** Answer open-ended multilingual questions using only local document context.

### Model and Inference

- Download Qwen3-1.7B Q4_K_M GGUF on first use with consent.
- Show download progress and verify the SHA-256 hash.
- Avoid metered downloads without explicit approval.
- Integrate llama.cpp through JNI and CMake.
- Run inference in a Foreground Service.
- Stream batched tokens, support cancellation, unload the model, and guard against severe thermal conditions.
- Trim context when the token limit is reached.

### Embeddings and Retrieval

- Bundle all-MiniLM-L6-v2 and its WordPiece vocabulary in assets.
- Generate 384-dimensional vectors and store them as BLOBs in `document_chunks`.
- Build cosine similarity retrieval with lazy-loaded embeddings.
- Process large embedding sets in batches.
- Implement the required four-tier query cascade:
  1. Field lookup for exact structured queries.
  2. FTS5 BM25 for strong keyword matches.
  3. Combined FTS5 and cosine retrieval for weak or semantic matches.
  4. Immediate `NO_CONTEXT` response when no relevant result exists.
- Combine normalized BM25 and cosine scores with equal weights.
- Send the top three chunks to the LLM.
- Require the LLM to answer only from supplied context and never invent sensitive data.
- Append document type and page citations.
- Log only the selected tier in debug builds, never query content.

### Performance Targets

- Tier 1: below 10 ms on a 4 GB device.
- Tier 2: below 80 ms on a 4 GB device.
- Tier 3: below 400 ms for 1,000 chunks on a 4 GB device.
- Query embedding: below 100 ms on a 4 GB device.
- LLM first token: below 15 seconds on a 4 GB device.
- Full response: below 40 seconds on a 4 GB device.

**Deliverable:** Exact queries respond immediately, semantic queries use hybrid retrieval and the local LLM, and irrelevant queries avoid LLM inference.

## Phase 7: Hardening, Testing, and Polish

**Duration:** 7-10 days

**Purpose:** Prepare the app for production use and verify privacy, reliability, and usability.

**Work:**
- Audit release network behavior and confirm zero network calls.
- Verify memory clearing, database encryption, and absence of sensitive Logcat values.
- Verify FLAG_SECURE on all sensitive screens.
- Test on budget 4 GB, mid-range 8 GB, and high-end 12 GB devices.
- Test OCR on 20 real documents across five document types.
- Measure LLM first-token and full-response latency.
- Confirm no OOM when the LLM is loaded with at least three indexed documents.
- Add onboarding, empty states, and error states.
- Support accessibility, large text, and TalkBack.
- Add settings for clearing data, re-downloading the model, language preferences, and storage/index statistics.
- Confirm backups are disabled, no analytics SDK exists, SDK levels are current, and release ProGuard rules preserve ML Kit and llama classes.
- Sign the release build with a securely backed-up release keystore.

**Deliverable:** A security-audited, tested, accessible, production-ready offline app.

## Recommended MVP

For an approximately four-week demo, implement only:

1. Phase 0 in full.
2. Phase 1 in full.
3. Phase 2 image OCR only; skip PDF processing.
4. Phase 3 MRZ/passport extraction only.
5. Phase 4 in full.
6. Phase 5 keyword text queries only; skip voice.

**MVP result:** The user selects a passport photo, extracts passport fields, queries them by text, and keeps the data encrypted on-device.

## Overall Timeline

- Phase 0: 3-5 days
- Phase 1: 4-5 days
- Phase 2: 10-14 days
- Phase 3: 10-14 days
- Phase 4: 5-7 days
- Phase 5: 6-8 days
- Phase 6: 12-16 days
- Phase 7: 7-10 days
- Total: approximately 55-78 days for one developer
verify by analyse it based 