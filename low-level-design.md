# StegoCLI — Low Level Design (LLD)

> Class-level design, method contracts, and algorithms. For the bird's-eye view
> and flows, see [`high-level-design.md`](./high-level-design.md).

---

## 1. Package Layout

```
com.stegocli
├── StegoApplication            // main(), Picocli root command
├── cli
│   ├── EncodeCommand          // `steg encode`
│   └── DecodeCommand          // `steg decode`
├── service
│   └── SteganographyService   // orchestration
├── crypto
│   ├── CryptoService          // PBKDF2 + AES-256-GCM
│   └── EncryptedPayload       // value object: salt + iv + ciphertext
├── payload
│   └── PayloadCodec           // header (magic/version/algo/len) + body byte framing
├── image
│   ├── ImageProcessor         // sequential LSB driver, load/write
│   ├── EncodingStrategy       // interface
│   ├── Lsb1BitStrategy
│   ├── Lsb2BitStrategy
│   └── StrategyRegistry       // --algorithm flag → strategy
└── exception
    ├── StegoException         // base (unchecked)
    ├── BadPasswordException
    ├── CapacityExceededException
    ├── UnsupportedFormatException
    ├── InvalidInputException
    └── ImageReadException
```

---

## 2. CLI Layer (Picocli)

### `StegoApplication`

```java
@Command(name = "steg", mixinStandardHelpOptions = true, version = "StegoCLI 1.0",
         subcommands = { EncodeCommand.class, DecodeCommand.class })
public final class StegoApplication implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new StegoApplication())
                .setExecutionExceptionHandler(new StegExceptionHandler())
                .execute(args);
        System.exit(exitCode);
    }
    @Override public void run() { /* no subcommand → print usage */ }
}
```

A single `StegExceptionHandler` maps each exception type to its exit code (see §9), so commands
throw freely and never call `System.exit` directly.

### `EncodeCommand`

```java
@Command(name = "encode", description = "Hide an encrypted message inside a cover image")
public final class EncodeCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"},    required = true) Path input;
    @Option(names = {"-m", "--message"},  required = true) String message;
    @Option(names = {"-p", "--password"}, required = true, arity = "0..1", interactive = true) char[] password;
    @Option(names = {"-o", "--output"})                    Path output;
    @Option(names = {"-a", "--algorithm"}, defaultValue = "lsb1") String algorithm;
    @Option(names = {"-v", "--verbose"})                   boolean verbose;

    @Override public Integer call() {
        // 1. validate input (exists, decodable image, message non-empty, password length)
        // 2. resolve strategy + output path
        // 3. delegate to SteganographyService.encode(...)
        // 4. render result to stdout
        return 0;
    }
}
```

`interactive = true` lets the password be prompted (and hidden) if not passed inline, avoiding
secrets in shell history. `password` is a `char[]` so it can be zeroed after use.

`DecodeCommand` mirrors this with `-i` and `-p` only.

---

## 3. Orchestration Layer

### `SteganographyService`

Pure orchestration — no crypto, no pixel math, and no I/O of its own.

```java
public final class SteganographyService {

    private final CryptoService crypto;
    private final StrategyRegistry registry;

    public EncodeResult encode(EncodeRequest req) {
        long start = System.nanoTime();
        EncodingStrategy strategy = registry.resolve(req.algorithm());
        EncryptedPayload payload  = crypto.encrypt(req.message(), req.password());
        ImageProcessor processor  = new ImageProcessor();
        // The processor decodes the cover once, capacity-checks, then embeds the
        // 1-bit header (built from strategy.id + body length) followed by the body.
        long bytesEmbedded = processor.embed(req.input(), req.output(), payload, strategy);
        return new EncodeResult(req.output(), bytesEmbedded, durationMs(start));
    }

    public DecodeResult decode(DecodeRequest req) { /* symmetric: processor.extract → decrypt */ }
}
```

Any `StegoException` thrown by the layers below propagates untouched to the CLI handler, which maps
it to the right exit code. The service neither catches nor logs it.

---

## 4. Crypto Layer

### `CryptoService`

```java
public final class CryptoService {

    private static final int    SALT_LEN     = 16;   // bytes
    private static final int    IV_LEN       = 12;   // bytes (GCM standard)
    private static final int    KEY_BITS     = 256;
    private static final int    PBKDF2_ITERS = 65_536;
    private static final int    GCM_TAG_BITS = 128;
    private static final String KDF          = "PBKDF2WithHmacSHA256";
    private static final String CIPHER       = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();

    public EncryptedPayload encrypt(String plaintext, char[] password) {
        byte[] salt = randomBytes(SALT_LEN);
        byte[] iv   = randomBytes(IV_LEN);
        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return new EncryptedPayload(salt, iv, ct);   // ct already includes the 16-byte tag
    }

    public String decrypt(EncryptedPayload p, char[] password) {
        try {
            SecretKey key = deriveKey(password, p.salt());
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, p.iv()));
            byte[] pt = cipher.doFinal(p.ciphertext());
            return new String(pt, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new BadPasswordException("Incorrect password or corrupted payload");
        }
    }

    private SecretKey deriveKey(char[] password, byte[] salt) {
        SecretKeyFactory f = SecretKeyFactory.getInstance(KDF);
        KeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERS, KEY_BITS);
        return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
    }
}
```

Key points:
- **GCM gives free integrity + authentication.** A wrong password derives a wrong key, the tag fails,
  `AEADBadTagException` is thrown, and we surface `BadPasswordException`. No separate "is the password
  right" check is needed.
- **IV is 12 bytes** — the recommended GCM nonce length; avoids internal re-hashing.
- A fresh salt and IV are generated **per operation**, so encoding the same message twice yields
  different pixels.

### `EncryptedPayload` (immutable value object)

```java
public record EncryptedPayload(byte[] salt, byte[] iv, byte[] ciphertext) {}
```

---

## 5. Image Layer

### `EncodingStrategy` (interface)

```java
public interface EncodingStrategy {
    int  id();                                       // 1 (lsb1) / 2 (lsb2) — stored in the header
    int  bitsPerChannel();                           // 1 or 2
    long capacityBytes(long channels);               // channels * bitsPerChannel / 8

    /** Write all bits of {@code data} into channel LSBs starting at channel {@code startChannel}. */
    void   embed(int[] pixels, byte[] data, int startChannel);

    /** Read {@code byteCount} bytes from channel LSBs starting at channel {@code startChannel}. */
    byte[] extract(int[] pixels, int byteCount, int startChannel);
}
```

A "channel" is one R/G/B slot; channels are visited R, G, B per pixel, pixels in row-major order. The
strategy is pure bit-twiddling — it knows nothing about the payload format. Framing lives entirely in
`PayloadCodec` (§6); the strategy just reads/writes a run of bits at a given offset. The
`startChannel` parameter is what lets the always-LSB-1 header and the algorithm-specific body coexist
in one image (see `ImageProcessor` below). Each strategy owns its capacity math, so adding a new
algorithm never touches the service.

### `StrategyRegistry`

```java
public final class StrategyRegistry {
    private final Map<String, EncodingStrategy> byFlag = Map.of(
        "lsb1", new Lsb1BitStrategy(),
        "lsb2", new Lsb2BitStrategy());

    /** Resolve the CLI --algorithm flag (encode path). */
    public EncodingStrategy resolve(String flag) {
        EncodingStrategy s = byFlag.get(flag.toLowerCase());
        if (s == null) throw new InvalidInputException("Unknown algorithm: " + flag);
        return s;
    }

    /** Resolve the algorithm id stored in a decoded header (decode path). */
    public EncodingStrategy byId(int id) {
        return byFlag.values().stream().filter(s -> s.id() == id).findFirst()
            .orElseThrow(() -> new BadPasswordException("Unknown algorithm id in payload: " + id));
    }
}
```

### Bit-to-pixel mapping (LSB-1, the core math)

The payload is a stream of bits. Channels are visited in order R, G, B per pixel, pixels in
row-major order. For payload bit index `b` (0-based):

```
channelIndex = b                       // one bit per channel for LSB-1
pixelIndex   = channelIndex / 3
channel      = channelIndex % 3        // 0=R, 1=G, 2=B
x = pixelIndex % width
y = pixelIndex / width
```

Embed sets the LSB of the chosen channel to the payload bit:

```
channelValue = (channelValue & 0xFE) | payloadBit;
```

For **LSB-2**, two bits go per channel, so `channelIndex = b / 2`, the bit-pair is `b % 2`, and the
write masks the low two bits: `(channelValue & 0xFC) | twoBitValue`.

Because the mapping from bit index to (pixel, channel) is a pure function, embedding and extraction
are a single deterministic pass over the pixel array in row-major order.

### `ImageProcessor`

`ImageProcessor` is stateless — the strategy is passed per call. It decodes the cover once, embeds
the fixed 8-byte header at **1 bit/channel** (so decode can always read it), then embeds the body at
the chosen strategy's bit depth in the channels after the header.

```java
public final class ImageProcessor {

    private static final EncodingStrategy HEADER_STRATEGY = new Lsb1BitStrategy();
    private static final int HEADER_CHANNELS = PayloadCodec.HEADER_BYTES * 8;   // 64 channels @ 1 bpc

    /** @return number of body bytes embedded. */
    public long embed(Path in, Path out, EncryptedPayload payload, EncodingStrategy strategy) {
        BufferedImage img = readImage(in);              // any format → raster (UnsupportedFormat/ImageRead)
        int w = img.getWidth(), h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        long totalChannels = (long) w * h * 3;

        byte[] body   = PayloadCodec.encodeBody(payload);
        byte[] header = PayloadCodec.encodeHeader(strategy.id(), body.length);

        long bodyCapacity = strategy.capacityBytes(totalChannels - HEADER_CHANNELS);
        if (body.length > bodyCapacity)
            throw new CapacityExceededException(body.length, bodyCapacity);

        HEADER_STRATEGY.embed(pixels, header, 0);                 // 8 bytes @ 1 bpc, channels [0,64)
        strategy.embed(pixels, body, HEADER_CHANNELS);            // body @ strategy bpc, from channel 64

        img.setRGB(0, 0, w, h, pixels, 0, w);
        writePng(img, out);                                       // always lossless PNG
        return body.length;
    }

    public EncryptedPayload extract(Path in, StrategyRegistry registry) {
        BufferedImage img = readImage(in);
        int w = img.getWidth(), h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);

        byte[] headerBytes = HEADER_STRATEGY.extract(pixels, PayloadCodec.HEADER_BYTES, 0);
        PayloadCodec.Header header = PayloadCodec.decodeHeader(headerBytes);   // validates magic/version

        EncodingStrategy strategy = registry.byId(header.algorithmId());
        long bodyCapacity = strategy.capacityBytes((long) w * h * 3 - HEADER_CHANNELS);
        if (header.bodyLength() > bodyCapacity)
            throw new BadPasswordException("Corrupt payload (declared length exceeds image capacity).");

        byte[] body = strategy.extract(pixels, header.bodyLength(), HEADER_CHANNELS);
        return PayloadCodec.decodeBody(body);
    }
}
```

### Processing model

Encoding and decoding are **single-threaded**. The strategy walks the `int[] pixels` array once,
embedding (or reading) one payload bit per colour channel. LSB work is dominated by memory access
rather than CPU, so a tight sequential loop is both simpler and fast enough for the target image
sizes; concurrency was deliberately left out of v1.0 to keep the engine easy to reason about and
test. If a progress indicator is shown, it is updated at a coarse interval (e.g. every N rows), not
per pixel.

> The `EncodingStrategy.embed`/`extract` contract is range-agnostic, so a future parallel
> implementation could be slotted in behind the same interface without touching `ImageProcessor`'s
> callers — but that is explicitly out of scope for v1.0.

### Image-format handling

The cover image may be **any format the JVM's `ImageIO` can decode** (PNG, JPEG, BMP, GIF, WBMP, and
any others provided by installed readers). `readImage` does not whitelist extensions — it asks
`ImageIO` to decode and classifies the outcome:

```java
private BufferedImage readImage(Path in) {
    try {
        BufferedImage img = ImageIO.read(in.toFile());
        if (img == null) {
            // No registered reader recognised the file as an image.
            throw new UnsupportedFormatException(
                "Unsupported or unrecognised image format: " + in.getFileName());
        }
        return img;
    } catch (IOException e) {
        // A reader exists but decoding failed (truncated/corrupt).
        throw new ImageReadException("Could not read image: " + in.getFileName(), e);
    }
}
```

**Output is always a lossless PNG**, regardless of input format:

```java
private void writePng(BufferedImage img, Path out) {
    try {
        ImageIO.write(img, "png", out.toFile());
    } catch (IOException e) {
        throw new StegoException("Failed to write output PNG: " + out, e);
    }
}
```

This asymmetry is intentional and load-bearing. LSB data lives in the lowest bit of each pixel; a
lossy encoder (JPEG) recompresses via a DCT transform that perturbs pixel values and destroys those
bits. So a JPEG cover is fine (we only read its pixels), but the encoded result must be PNG. When the
user does not pass `-o`, the default output name is derived as `<input-basename>_encoded.png` — the
extension is forced to `.png` even if the input was `photo.jpg`.

> Decoding any format to an `int[]` RGB raster also normalises palette/grayscale/CMYK images to
> truecolor RGB, so the capacity formula (3 channels per pixel) holds uniformly for every input.

---

## 6. Payload Framing — `PayloadCodec`

The embedded bytes are split into a fixed **header** and a variable **body**, written into the image
at different bit depths (see §5 / the embedding plan):

```
HEADER (8 bytes, always embedded at 1 bit/channel)
  [0..1]  magic  = 'S','G'        identifies a StegoCLI payload
  [2]     version                  payload format version (currently 1)
  [3]     algorithmId              1 = LSB-1, 2 = LSB-2  (how the BODY was embedded)
  [4..7]  bodyLength               big-endian int: number of body bytes that follow

BODY (bodyLength bytes, embedded at the algorithm's bit depth)
  [0..15]   salt                   16 bytes
  [16..27]  iv                     12 bytes
  [28..]    ciphertext + GCM tag   remainder
```

`PayloadCodec` is pure (no I/O, no image awareness) and exposes four static methods:

```java
public final class PayloadCodec {
    public static final byte VERSION      = 1;
    public static final int  HEADER_BYTES = 8;       // magic(2)+version(1)+algo(1)+len(4)

    public static byte[]  encodeHeader(int algorithmId, int bodyLength); // -> 8 bytes
    public static Header   decodeHeader(byte[] header);                  // validates magic+version
    public static byte[]  encodeBody(EncryptedPayload p);                // salt|iv|ciphertext
    public static EncryptedPayload decodeBody(byte[] body);              // split back out

    public record Header(int version, int algorithmId, int bodyLength) {}
}
```

Why a header (rather than the earlier bare `[length][salt][iv][ct]`):
- **Magic bytes** let `decode` recognise an image that was never encoded and fail cleanly
  (`BadPasswordException`, "no StegoCLI message found") instead of misreading random LSBs.
- **Version** byte allows the format to evolve without silently misparsing old images.
- **algorithmId** makes the payload self-describing: `decode` reads it from the always-LSB-1 header
  and then knows the bit depth to read the body — so `decode` needs no `--algorithm` flag.

On decode the processor reads the 8-byte header first, validates the magic, then reads exactly
`bodyLength` body bytes at the indicated bit depth — so it never over-reads the image.

---

## 7. Exception Hierarchy & Exit Mapping

```
StegoException (RuntimeException, base)
├── BadPasswordException          → exit 1
├── CapacityExceededException     → exit 1
├── UnsupportedFormatException    → exit 1
├── InvalidInputException         → exit 1
└── ImageReadException            → exit 2
```

```java
final class StegExceptionHandler implements IExecutionExceptionHandler {
    public int handle(Exception ex, CommandLine cmd, ParseResult pr) {
        if (ex instanceof BadPasswordException
         || ex instanceof CapacityExceededException
         || ex instanceof UnsupportedFormatException
         || ex instanceof InvalidInputException) {
            cmd.getErr().println("ERROR: " + ex.getMessage());
            return 1;
        }
        cmd.getErr().println("INTERNAL ERROR: " + ex.getMessage());
        return 2;
    }
}
```

All exceptions are **unchecked** (extend `RuntimeException`) so the service and image layers stay
free of `throws` clutter; the handler is the single place that maps them to user-facing output.

---

## 8. Testing Strategy (JUnit 5 + Mockito)

| Layer | What is tested | How |
|---|---|---|
| `CryptoService` | round-trip encrypt→decrypt; wrong password → `BadPasswordException`; salt/IV randomness | pure unit, no mocks |
| `PayloadCodec` | header encode/decode (magic, version, algo, length); body split round-trip; missing magic → `BadPasswordException` | pure unit |
| Strategies | embed→extract round-trip on a synthetic `int[]`; capacity math; LSB-1 vs LSB-2 | pure unit |
| `ImageProcessor` | capacity rejection; any-format input (PNG/JPEG/BMP); PNG output decodes identically | unit + temp files |
| `SteganographyService` | orchestration order (resolve strategy → encrypt → embed) | Mockito mocks for crypto + processor |
| End-to-end | `encode` then `decode` via the CLI yields the original message (incl. a JPEG cover) | full-stack test on a fixture image |

Key invariant under test: **for any message M ≤ capacity and password P,
`decode(encode(M, P), P) == M`, and `decode(encode(M, P), P') ` throws for `P' ≠ P`.**