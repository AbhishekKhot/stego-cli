<p align="center">
  <img src="assets/logo.svg" alt="StegoCLI logo" width="140" height="140">
</p>

<h1 align="center">StegoCLI</h1>

<p align="center">
  A terminal-first steganography tool in Java that hides <strong>AES-256-GCM-encrypted</strong> text
  inside images — any readable format in, <strong>lossless PNG</strong> out — using LSB embedding.
</p>

---

StegoCLI does not just hide text in pixels. It **encrypts the payload before embedding it**, so the
hidden data is ciphertext — useless to anyone who detects and extracts it without the password. It is
fully scriptable with no GUI, no network layer, and no database — it drops cleanly into shell
scripts and pipelines.

---

## Table of Contents

- [Why steganography + encryption](#why-steganography--encryption)
- [Features](#features)
- [Image Format Support](#image-format-support)
- [Tech Stack](#tech-stack)
- [Architecture at a Glance](#architecture-at-a-glance)
- [Design Documentation](#design-documentation)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Usage](#usage)
- [Project Structure](#project-structure)
- [Exit Codes](#exit-codes)
- [License](#license)

---

## Why steganography + encryption

Encryption makes a message *unreadable*; it does not hide the *fact that a secret exists*. An
intercepted encrypted blob is itself a red flag. Steganography closes that gap by concealing the
message inside an ordinary-looking carrier file.

StegoCLI combines both layers so the hidden data is invisible **and** protected:

- **Data exfiltration over monitored channels** — a message hidden in an ordinary PNG slips past
  filters that scan for encrypted blobs.
- **Metadata-stripped medical records** — patient identifiers embedded in the pixel stream survive
  renaming and metadata stripping.
- **Proof of digital ownership** — an invisible signature embedded in an exported asset can be
  extracted later to prove authorship.
- **Secure offline secret distribution** — short secrets (API keys, OTPs) ride inside an innocent
  image and stay encrypted at rest.

---

## Features

| Area | Highlights |
|---|---|
| **Core engine** | LSB-1 and LSB-2 embedding; pluggable `EncodingStrategy` (Strategy Pattern); capacity validation before embedding; lossless PNG output |
| **Security** | AES-256-GCM authenticated encryption; PBKDF2WithHmacSHA256 key derivation (65,536 iterations); random per-operation salt + IV; wrong password caught via GCM auth-tag failure |
| **File I/O** | Reads any image format your `ImageIO` can decode (PNG, JPEG, BMP, GIF, …); always writes a lossless PNG; NIO.2 `Path` API throughout |
| **CLI** | Picocli subcommands (`encode`, `decode`); typed options; structured exit codes; `--help`/`--version` |
| **Observability** | SLF4J + Logback structured logging; typed exception hierarchy; throughput metrics on every run |

A full breakdown lives in the [design docs](#design-documentation).

---

## Image Format Support

**Input (cover image):** any format your installed `ImageIO` readers can decode — PNG, JPEG, BMP,
GIF, WBMP, and more. The cover is decoded to raw pixels, so its original format does not matter.

**Output (stego image): always a lossless PNG.** This is a hard requirement, not a preference.

> **Why output can't be JPEG.** LSB steganography hides data in the lowest bit of each pixel. JPEG
> is *lossy* — saving recompresses the image with a DCT transform that changes pixel values, which
> wipes out exactly those low bits. A "JPEG stego image" would silently lose its hidden message on
> save and fail to decode. So you may freely use a **JPEG as the cover**, but the encoded result is
> written as PNG (the default output name becomes `<input>_encoded.png` regardless of input type).

The same applies to any other lossy format: read freely, write PNG.

---

## Tech Stack

| Concern | Choice |
|---|---|
| Language | Java 17+ |
| CLI framework | Picocli |
| Cryptography | `javax.crypto` (AES-256-GCM, PBKDF2WithHmacSHA256) |
| Image I/O | `javax.imageio.ImageIO`, `java.nio.file` |
| Logging | SLF4J + Logback |
| Testing | JUnit 5 + Mockito |
| Build | Maven |

---

## Architecture at a Glance

Single-process, CLI-driven, local-only. No REST API, no client-server boundary, no database — and
no persistent state beyond the output image it writes.

```
Terminal ──▶ CLI Layer (Picocli) ──▶ SteganographyService ──▶ CryptoService
                                              │
                                              └──▶ ImageProcessor ──▶ LSB Strategy (sequential)
```

See [`high-level-design.md`](./high-level-design.md) for the full component map and flows.

---

## Design Documentation

| Document | Purpose |
|---|---|
| [`high-level-design.md`](./high-level-design.md) | System context, component overview, encode/decode flows, payload binary layout, capacity formula, CLI wireframes, error catalogue |
| [`low-level-design.md`](./low-level-design.md) | Package and class breakdown, method signatures, bit-embedding math, crypto parameters, image-format handling, exception hierarchy, testing strategy |

---

## Prerequisites

- JDK 17 or later
- Maven 3.8+

---

## Build

```bash
mvn clean package
```

This produces an executable fat JAR at `target/stegocli.jar`. For convenience, alias it:

```bash
alias steg='java -jar /path/to/stegocli.jar'
```

---

## Usage

### Encode — hide a message

```bash
steg encode -i cover.png -m "deploy key: abc123" -p "myPassword" -o secret.png
```

```
[steg] Reading image       cover.png  (800x600, 1.4 MB)
[steg] Encrypting payload  18 bytes -> 50 bytes (AES-256-GCM)
[steg] Capacity check      50 bytes required / 175,781 bytes available  OK
[steg] Encoding            ==================== 100%
[steg] Writing output      secret.png

  Output : /home/user/secret.png
  Payload: 50 bytes embedded
  Time   : 138 ms  |  Throughput: 10.4 MB/s
```

### Decode — extract a message

```bash
steg decode -i secret.png -p "myPassword"
```

```
[steg] Reading image    secret.png  (800x600, 1.4 MB)
[steg] Extracting bits  ==================== 100%
[steg] Decrypting       50 bytes -> 18 bytes (AES-256-GCM)

  Message: deploy key: abc123
  Time   : 94 ms
```

### Options

```
steg encode -i <file> -m <message> -p <password> [OPTIONS]

  -i, --input      <file>      Cover image (any readable format: PNG, JPEG, BMP, GIF, ...)
  -m, --message    <text>      Secret message to embed
  -p, --password   <password>  Encryption password (prompted; input hidden)
  -o, --output     <file>      Output path, always PNG (default: <input>_encoded.png)
  -a, --algorithm  <name>      lsb1 (default) | lsb2
  -v, --verbose                Detailed processing logs
  -h, --help                   Show help
```

---

## Project Structure

```
stegocli/
├── README.md
├── high-level-design.md
├── low-level-design.md
├── pom.xml
├── .gitignore
├── assets/                                   # logo + social preview image
│   ├── logo.svg
│   ├── logo.png
│   └── social-preview.png
└── src/
    ├── main/
    │   ├── java/com/stegocli/
    │   │   ├── StegApplication.java          # Picocli root + main
    │   │   ├── cli/                          # EncodeCommand, DecodeCommand
    │   │   ├── service/                      # SteganographyService
    │   │   ├── crypto/                        # CryptoService, EncryptedPayload
    │   │   ├── image/                         # ImageProcessor, strategies
    │   │   └── exception/                     # StegoException hierarchy
    │   └── resources/
    │       └── logback.xml
    └── test/java/com/stegocli/               # JUnit 5 + Mockito
```

---

## Exit Codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | User error (bad password, wrong format, insufficient capacity, invalid input) |
| `2` | Internal error (unreadable image, unexpected failure) |

---

## License

MIT (placeholder — confirm before publishing).