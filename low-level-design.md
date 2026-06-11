# StegoCLI — Low Level Design (LLD)

> Class-level design, method contracts, and algorithms. For the bird's-eye view
> and flows, see [`high-level-design.md`](./high-level-design.md).

---

## 1. Package Layout

```
com.stegocli
├── StegApplication            // main(), Picocli root command
├── cli
│   ├── EncodeCommand          // `steg encode`
│   └── DecodeCommand          // `steg decode`
├── service
│   └── SteganographyService   // orchestration
├── crypto
│   ├── CryptoService          // PBKDF2 + AES-256-GCM
│   └── EncryptedPayload       // value object: salt + iv + ciphertext
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

### `StegApplication`

```java
@Command(name = "steg", mixinStandardHelpOptions = true, version = "StegoCLI 1.0",
         subcommands = { EncodeCommand.class, DecodeCommand.class })
public final class StegApplication implements Runnable {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new StegApplication())
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
        byte[] framed             = PayloadCodec.frame(payload);     // §6
        ImageProcessor processor  = new ImageProcessor(strategy);
        processor.assertCapacity(req.input(), framed.length);
        processor.embed(req.input(), req.output(), framed);
        return new EncodeResult(req.output(), framed.length, durationMs(start));
    }

    public DecodeResult decode(DecodeRequest req) { /* symmetric */ }
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
    String name();                                   // "lsb1" / "lsb2"
    int    bitsPerChannel();                         // 1 or 2
    long   capacityBytes(int width, int height);     // (w*h*3*bitsPerChannel)/8
    void   embed(int[] pixels, byte[] payload);      // in-place LSB write
    byte[] extract(int[] pixels);                    // reads length header, returns framed payload
}
```

Each strategy owns its own capacity math, so adding a new algorithm never touches the service.

### `StrategyRegistry`

```java
public final class StrategyRegistry {
    private final Map<String, EncodingStrategy> byName = Map.of(
        "lsb1", new Lsb1BitStrategy(),
        "lsb2", new Lsb2BitStrategy());

    public EncodingStrategy resolve(String flag) {
        EncodingStrategy s = byName.get(flag.toLowerCase());
        if (s == null) throw new InvalidInputException("Unknown algorithm: " + flag);
        return s;
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

```java
public final class ImageProcessor {
    private final EncodingStrategy strategy;

    public ImageProcessor(EncodingStrategy strategy) {
        this.strategy = strategy;
    }

    public void assertCapacity(Path image, int payloadBytes) {
        Dimension d = readDimensions(image);            // header read, no full decode
        long cap = strategy.capacityBytes(d.width, d.height);
        if (payloadBytes > cap)
            throw new CapacityExceededException(payloadBytes, cap);
    }

    public void embed(Path in, Path out, byte[] payload) {
        BufferedImage img = readImage(in);              // ImageIO decode → raster
        int[] pixels = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        strategy.embed(pixels, payload);                // single sequential pass
        img.setRGB(0, 0, img.getWidth(), img.getHeight(), pixels, 0, img.getWidth());
        writePng(img, out);                             // lossless
    }

    public byte[] extract(Path in) { /* symmetric, returns framed payload bytes */ }
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

```java
public final class PayloadCodec {
    // frame: [4-byte length][salt][iv][ciphertext]
    public static byte[] frame(EncryptedPayload p) {
        int bodyLen = p.salt().length + p.iv().length + p.ciphertext().length;
        ByteBuffer buf = ByteBuffer.allocate(4 + bodyLen);
        buf.putInt(bodyLen);                 // big-endian by default
        buf.put(p.salt()).put(p.iv()).put(p.ciphertext());
        return buf.array();
    }

    public static EncryptedPayload unframe(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        byte[] salt = new byte[16]; buf.get(salt);
        byte[] iv   = new byte[12]; buf.get(iv);
        byte[] ct   = new byte[buf.remaining()]; buf.get(ct);
        return new EncryptedPayload(salt, iv, ct);
    }
}
```

On decode the processor first extracts the 32 length bits, reads the `int`, then extracts exactly
that many bytes — so it never over-reads the image.

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
| `PayloadCodec` | frame→unframe round-trip; length boundary | pure unit |
| Strategies | embed→extract round-trip on a synthetic `int[]`; capacity math; LSB-1 vs LSB-2 | pure unit |
| `ImageProcessor` | capacity rejection; any-format input (PNG/JPEG/BMP); PNG output decodes identically | unit + temp files |
| `SteganographyService` | orchestration order (encrypt → frame → capacity → embed) | Mockito mocks for crypto + processor |
| End-to-end | `encode` then `decode` via the CLI yields the original message (incl. a JPEG cover) | full-stack test on a fixture image |

Key invariant under test: **for any message M ≤ capacity and password P,
`decode(encode(M, P), P) == M`, and `decode(encode(M, P), P') ` throws for `P' ≠ P`.**