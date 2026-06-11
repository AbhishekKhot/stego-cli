# StegoCLI — High Level Design (HLD)

> This document describes the system at the component and flow level. For class-level detail,
> method signatures, and algorithms, see [`low-level-design.md`](./low-level-design.md).

---

## 1. System Context

StegoCLI is a single-process, CLI-driven local tool. There is **no network layer, no REST API, no
client-server boundary, and no database**. All processing happens on the user's machine and the only
output is the encoded image file. It has no external runtime dependencies.

```
┌─────────────────────────────────────────────────────────────────┐
│                        User's Machine                            │
│                                                                  │
│   Terminal                                                       │
│   $ steg encode -i photo.jpg -m "secret" -p password             │
│        │                                                         │
│        ▼                                                         │
│   ┌─────────────┐     ┌──────────────────┐     ┌──────────────┐  │
│   │  CLI Layer  │────▶│  Processing Core │────▶│ File System   │ │
│   │  (Picocli)  │     │  (Encode/Decode) │     │ any image in, │ │
│   └─────────────┘     └──────────────────┘     │ PNG out       │ │
│                                                └──────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**Input/output principle:** the cover image may be *any* format the JVM's `ImageIO` can decode
(PNG, JPEG, BMP, GIF, …) because it is decoded to raw pixels before processing. The encoded result
is **always written as a lossless PNG** — lossy re-encoding (e.g. JPEG) would destroy the LSB data.

---

## 2. Component Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                          CLI Entry Point                              │
│                       StegApplication (main)                          │
│              Picocli root command + subcommand router                 │
└───────────────────────────┬──────────────────────────────────────────┘
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
   ┌─────────────────┐         ┌─────────────────┐
   │  EncodeCommand  │         │  DecodeCommand  │
   │  (Picocli sub)  │         │  (Picocli sub)  │
   └────────┬────────┘         └────────┬────────┘
            │                           │
            └─────────────┬─────────────┘
                          ▼
   ┌──────────────────────────────────────────────┐
   │              SteganographyService              │
   │     Orchestrates encrypt → embed (encode)      │
   │     Orchestrates extract → decrypt (decode)    │
   └──────┬───────────────────────┬─────────────────┘
          │                       │
          ▼                       ▼
   ┌──────────────┐       ┌─────────────────┐
   │ CryptoService│       │  ImageProcessor  │
   │ AES-256 GCM  │       │  sequential LSB  │
   │ PBKDF2 keys  │       │  LSB strategy    │
   └──────────────┘       └────────┬─────────┘
                                   │
                     ┌─────────────┴─────────────┐
                     ▼                           ▼
           ┌──────────────────┐       ┌──────────────────┐
           │ LSB1BitStrategy  │       │ LSB2BitStrategy  │
           │ (1 bit per RGB   │       │ (2 bits per RGB  │
           │  channel)        │       │  channel)        │
           └──────────────────┘       └──────────────────┘
```

| Component | Responsibility |
|---|---|
| `StegApplication` | Picocli root command, wires subcommands, owns process exit code |
| `EncodeCommand` / `DecodeCommand` | Parse + validate CLI input, delegate to the service, render output |
| `SteganographyService` | Orchestration only — sequences crypto, capacity check, and image processing |
| `CryptoService` | AES-256-GCM encrypt/decrypt, PBKDF2 key derivation, payload (de)serialization |
| `ImageProcessor` | Decodes any readable image, runs the selected strategy sequentially, writes PNG |
| `EncodingStrategy` (+ impls) | LSB embed/extract logic and per-strategy capacity calculation |

---

## 3. Encode Flow

```
User Input:  -i cover.jpg  -m "secret message"  -p password  [--algorithm lsb1]  [-o out.png]
  │
  ▼
1. CLI LAYER (EncodeCommand)
   ├── Validate input file exists and decodes as an image (any readable format)
   ├── Validate message is not empty
   ├── Validate password meets minimum length
   └── Resolve EncodingStrategy from --algorithm flag
        │
        ▼
2. CRYPTO LAYER (CryptoService)
   ├── Generate random 16-byte salt
   ├── Generate random 12-byte IV
   ├── Derive 256-bit AES key via PBKDF2WithHmacSHA256 (password + salt, 65,536 iterations)
   ├── Encrypt message bytes using AES-256-GCM (128-bit auth tag)
   └── Return EncryptedPayload { salt, iv, ciphertext+tag }
        │
        ▼
3. PAYLOAD ASSEMBLY
   └── Build binary payload:
         [ 4 bytes : length of (salt+iv+ciphertext) ]
         [ 16 bytes: salt ]
         [ 12 bytes: IV ]
         [ N bytes : ciphertext + GCM tag ]
        │
        ▼
4. CAPACITY CHECK (ImageProcessor / strategy)
   └── Assert max_payload_bits >= payload_length_in_bits
       throws CapacityExceededException if not
        │
        ▼
5. ENCODING (ImageProcessor)
   ├── Decode image to BufferedImage (ImageIO)
   ├── Walk pixels in row-major order
   ├── Embed payload bits into LSBs of R, G, B (one pass)
   └── Pixel raster fully written
        │
        ▼
6. OUTPUT WRITE
   └── Write modified raster to output PNG (lossless) via ImageIO.write()
        │
        ▼
7. STDOUT: output path, payload size, time, throughput
```

---

## 4. Decode Flow

```
User Input:  -i encoded.png  -p password
  │
  ▼
1. CLI LAYER (DecodeCommand)
   ├── Validate input file exists and decodes as an image
   └── Validate password is not empty
        │
        ▼
2. EXTRACTION (ImageProcessor)
   ├── Decode image to BufferedImage
   ├── Read first 32 bits from LSBs → payload length (4 bytes)
   ├── Walk pixels in row-major order
   └── Reassemble extracted bits into the encrypted byte array
        │
        ▼
3. PAYLOAD PARSE
   └── salt = bytes[0..15], iv = bytes[16..27], ciphertext = bytes[28..end]
        │
        ▼
4. CRYPTO LAYER (CryptoService)
   ├── Re-derive AES key via PBKDF2 (password + extracted salt)
   ├── AES-256-GCM decrypt (IV + ciphertext); GCM verifies the auth tag
   └── On tag mismatch → BadPasswordException
        │
        ▼
5. STDOUT: decoded message, time
```

---

## 5. Payload Binary Structure

```
 Byte offset    Content                          Size
 ───────────    ──────────────────────────────   ──────
 0  – 3         Payload length (big-endian int)   4 bytes   (length of bytes 4..N)
 4  – 19        Cryptographic salt                16 bytes
 20 – 31        AES-GCM initialisation vector     12 bytes
 32 – N         AES-256-GCM ciphertext + tag      variable  (tag is the trailing 16 bytes)

 Header overhead: 4 (length) + 16 (salt) + 12 (IV) = 32 bytes
 Plus 16-byte GCM tag inside the ciphertext block.
 Minimum capacity required: (32 + ciphertext_len) × 8 bits
```

The 4-byte length prefix is embedded in the clear because the decoder must know how many bits to
read before it can extract anything. The salt and IV are not secret by design — they are needed for
key derivation and decryption and add no value to an attacker without the password.

---

## 6. Image Capacity Formula

```
LSB-1 (1 bit per channel):
  max_payload_bits  = width × height × 3 × 1
  max_payload_bytes = max_payload_bits / 8

LSB-2 (2 bits per channel):
  max_payload_bits  = width × height × 3 × 2
  max_payload_bytes = max_payload_bits / 8

Example — 800×600 image, LSB-1:
  800 × 600 × 3 × 1 = 1,440,000 bits = 180,000 bytes ≈ 175 KB max payload
```

Only the R, G, B channels are used. The alpha channel (if present) is deliberately left untouched —
modifying alpha can produce visible artifacts on transparent pixels.

---

## 7. CLI Wireframes

### Encode — image too small

```
$ steg encode -i tiny.png -m "$(cat large_secret.txt)" -p "myPassword"

[steg] Reading image       tiny.png  (32x32, 3 KB)
[steg] Encrypting payload  4,200 bytes -> 4,232 bytes (AES-256-GCM)
[steg] Capacity check      4,232 bytes required / 384 bytes available  FAIL

ERROR: Image too small to hold the payload.
       Required : 4,232 bytes
       Available: 384 bytes
       Use a larger cover image or a shorter message.

Exit code: 1
```

### Decode — wrong password

```
$ steg decode -i secret.png -p "wrongPassword"

[steg] Reading image    secret.png  (800x600, 1.4 MB)
[steg] Extracting bits  ==================== 100%
[steg] Decrypting       failed

ERROR: Decryption failed. Incorrect password or image has not been encoded.

Exit code: 1
```

---

## 8. Error Catalogue

| Exit Code | Exception Class                 | Trigger                                       |
|-----------|---------------------------------|-----------------------------------------------|
| 0         | —                               | Success                                       |
| 1         | `BadPasswordException`          | GCM auth tag mismatch on decryption           |
| 1         | `CapacityExceededException`     | Payload exceeds image pixel capacity          |
| 1         | `UnsupportedFormatException`    | No `ImageIO` reader for the input file        |
| 1         | `InvalidInputException`         | Empty message, missing file, short password   |
| 2         | `ImageReadException`            | File has a reader but cannot be decoded        |
| 2         | `StegoException`                | Unexpected internal error (base class)        |