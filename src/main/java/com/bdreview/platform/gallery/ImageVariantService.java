package com.bdreview.platform.gallery;

import com.bdreview.platform.common.SharedCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * WebP variants of uploaded images (200 / 600 / 1200 px wide by default), stored next to the
 * original under {@link StorageUrlSigner#VARIANT_PREFIX}. {@code GET .../files/{key}?w=600} serves
 * one after the same access checks as the original; when a variant is missing the original is
 * served, so variants are an optimisation only and generation never fails an upload.
 *
 * <p>Generated on one background thread with a short queue: decoding a phone photo takes tens of MB,
 * and the API container is memory-limited. Identity documents and support screenshots get none.
 */
@Service
public class ImageVariantService {

    private static final Logger log = LoggerFactory.getLogger(ImageVariantService.class);
    private static final long MAX_SOURCE_PIXELS = 60_000_000L;
    private static final List<String> NO_VARIANTS = List.of("nid/", "claim-document/", "support/", StorageUrlSigner.VARIANT_PREFIX);

    private final ObjectStorageClient storage;
    private final SharedCache cache;
    private final boolean enabled;
    private final int[] widths;
    private final float quality;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), r -> {
                Thread t = new Thread(r, "image-variants");
                t.setDaemon(true);
                return t;
            }, (r, e) -> log.warn("Image variant queue full; skipping (the original is served instead)"));

    public ImageVariantService(ObjectStorageClient storage, SharedCache cache,
                               @Value("${app.storage.variants.enabled}") boolean enabled,
                               @Value("${app.storage.variants.widths}") int[] widths,
                               @Value("${app.storage.variants.quality}") float quality) {
        this.storage = storage;
        this.cache = cache;
        this.enabled = enabled;
        this.widths = Arrays.stream(widths).sorted().toArray();
        this.quality = quality;
    }

    public int[] widths() {
        return widths;
    }

    public static String variantKey(String key, int width) {
        return StorageUrlSigner.VARIANT_PREFIX + width + "/" + key + ".webp";
    }

    /** Shared-cache key remembering whether a variant exists (see StorageController). */
    public static String existsCacheKey(String variantKey) {
        return "storage:variant:" + variantKey;
    }

    /** The configured width to serve for a requested {@code ?w=}: the smallest one at least that wide. */
    public Integer pickWidth(Integer requested) {
        if (requested == null || requested <= 0) {
            return null;
        }
        for (int w : widths) {
            if (w >= requested) {
                return w;
            }
        }
        return widths[widths.length - 1];
    }

    public boolean wantsVariants(String key) {
        return enabled && StorageContentTypes.RESIZABLE.contains(StorageContentTypes.extension(key))
                && NO_VARIANTS.stream().noneMatch(key::startsWith);
    }

    /** Queues variant generation for a just-uploaded object. */
    public void generateLater(String key, byte[] original) {
        if (wantsVariants(key)) {
            executor.execute(() -> generateNow(key, original, false));
        }
    }

    /**
     * Generates the variants for one object. With {@code onlyMissing}, widths whose variant already
     * exists are skipped (the media migration re-runs safely). Returns how many were written.
     */
    public int generateNow(String key, byte[] original, boolean onlyMissing) {
        if (!wantsVariants(key)) {
            return 0;
        }
        try {
            BufferedImage source = decode(original);
            if (source == null) {
                return 0;
            }
            int written = 0;
            for (int w : widths) {
                String vKey = variantKey(key, w);
                if (onlyMissing && storage.size(vKey).isPresent()) {
                    continue;
                }
                BufferedImage scaled = scaleToWidth(source, Math.min(w, source.getWidth()));
                storage.putObject(vKey, encodeWebp(scaled), "image/webp");
                cache.evict(existsCacheKey(vKey));
                written++;
            }
            return written;
        } catch (Exception | OutOfMemoryError e) {
            log.warn("Could not generate image variants for {}: {}", key, e.toString());
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------ decode

    private BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if ((long) w * h > MAX_SOURCE_PIXELS) {
                    log.info("Skipping variants: image is {}x{}", w, h);
                    return null;
                }
                // Decode at most ~2x the largest variant: bounds memory for huge camera photos.
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, w / (widths[widths.length - 1] * 2));
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                return applyExifOrientation(image, exifOrientation(bytes));
            } finally {
                reader.dispose();
            }
        }
    }

    /** EXIF orientation (1-8) of a JPEG, or 1 when absent. ImageIO ignores it, so phone photos would come out sideways. */
    static int exifOrientation(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int i = 2;
        while (i + 4 < b.length && (b[i] & 0xFF) == 0xFF) {
            int marker = b[i + 1] & 0xFF;
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (marker == 0xE1 && i + 10 < b.length && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                int tiff = i + 10;
                boolean le = b[tiff] == 'I';
                int ifd = tiff + read32(b, tiff + 4, le);
                if (ifd + 2 > b.length) {
                    return 1;
                }
                int entries = read16(b, ifd, le);
                for (int e = 0; e < entries; e++) {
                    int entry = ifd + 2 + e * 12;
                    if (entry + 12 > b.length) {
                        return 1;
                    }
                    if (read16(b, entry, le) == 0x0112) {
                        int v = read16(b, entry + 8, le);
                        return v >= 1 && v <= 8 ? v : 1;
                    }
                }
                return 1;
            }
            if (marker == 0xDA) {
                return 1; // start of scan: no EXIF before the image data
            }
            i += 2 + len;
        }
        return 1;
    }

    private static int read16(byte[] b, int at, boolean le) {
        return le ? (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) : ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static int read32(byte[] b, int at, boolean le) {
        return le ? read16(b, at, true) | (read16(b, at + 2, true) << 16) : (read16(b, at, false) << 16) | read16(b, at + 2, false);
    }

    private static BufferedImage applyExifOrientation(BufferedImage img, int orientation) {
        if (orientation <= 1) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { return img; }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, img.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------------------------ scale + encode

    private static BufferedImage scaleToWidth(BufferedImage src, int targetW) {
        int targetH = Math.max(1, Math.round((float) src.getHeight() * targetW / src.getWidth()));
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage current = src;
        int w = src.getWidth();
        int h = src.getHeight();
        // Halve step by step while far above the target (keeps bilinear scaling sharp), then finish.
        do {
            w = Math.max(targetW, w / 2 >= targetW ? w / 2 : targetW);
            h = w == targetW ? targetH : Math.max(targetH, h / 2);
            BufferedImage next = new BufferedImage(w, h, type);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        } while (w > targetW);
        return current;
    }

    private byte[] encodeWebp(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) {
            throw new IOException("No WebP encoder available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] types = param.getCompressionTypes();
                if (types != null) {
                    for (String type : types) {
                        if (type.toLowerCase(Locale.ROOT).contains("lossy")) {
                            param.setCompressionType(type);
                        }
                    }
                }
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
