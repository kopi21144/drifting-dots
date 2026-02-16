/*
 * DriftingDots — Dot Master engine for crypto generative artwork.
 * Renders evolving dot fields from deterministic seeds; positions and hues derive from hash chains.
 * Suited for headless rendering and export; all configuration is fixed at construction.
 */

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// ---------------------------------------------------------------------------
// Unique seed constants (address-like, never reused in other contracts or apps)
// ---------------------------------------------------------------------------

public final class DriftingDots {

    public static final String GENESIS_SEED_A = "0x8b2f4c9e1a7d3f0b6e5c8a2d9f4e1b7c0a3d6e9";
    public static final String GENESIS_SEED_B = "0x1e7a3d9c2f5b8e0a4c7d1f6b9e2a5c8d0f3b6e1";
    public static final String GENESIS_SEED_C = "0xc4e7a0d3f6b9e2a5c8d1f4b7e0a3d6c9f2b5e8a";
    public static final String PALETTE_ANCHOR = "0xf2b5e8a1c4d7e0f3b6a9c2d5e8f1b4a7d0e3c6e9";
    public static final String DRIFT_ORACLE = "0xa7d0e3c6f9b2e5a8d1c4f7b0e3a6d9c2f5b8e1a4";
    public static final String TRAIL_HASH_SALT = "0x3f6b9e2a5c8d1f4b7e0a3d6c9f2b5e8a1d4c7f0b";
    public static final int DOT_CAPACITY = 4096;
    public static final int TRAIL_LENGTH = 32;
    public static final double DEFAULT_DRIFT_SCALE = 0.0004127;
    public static final int CANVAS_WIDTH_DEFAULT = 1920;
    public static final int CANVAS_HEIGHT_DEFAULT = 1080;
    public static final String ENGINE_VERSION = "DriftingDots-1.0.0";
    public static final int HASH_ITERATIONS = 3;
    public static final double PHASE_SPEED = 0.0003829;
    public static final int EXPORT_FORMAT_PNG = 1;
    public static final int EXPORT_FORMAT_RAW = 2;

    private final double driftScale;
    private final int canvasWidth;
    private final int canvasHeight;
    private final long constructionTimeMs;
    private final List<DotMasterListener> listeners = new CopyOnWriteArrayList<>();
    private final MessageDigest digest;
    private DotField field;
    private long tickCount;

    public DriftingDots() {
        this(DEFAULT_DRIFT_SCALE, CANVAS_WIDTH_DEFAULT, CANVAS_HEIGHT_DEFAULT);
    }

    public DriftingDots(double driftScale, int canvasWidth, int canvasHeight) {
        if (driftScale <= 0 || driftScale > 1.0) {
            throw new IllegalArgumentException("DriftingDots: drift scale out of range");
        }
        if (canvasWidth < 64 || canvasHeight < 64) {
            throw new IllegalArgumentException("DriftingDots: canvas dimensions too small");
        }
        if (canvasWidth > 16384 || canvasHeight > 16384) {
            throw new IllegalArgumentException("DriftingDots: canvas dimensions exceed cap");
        }
        this.driftScale = driftScale;
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.constructionTimeMs = System.currentTimeMillis();
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("DriftingDots: SHA-256 unavailable", e);
        }
        this.field = new DotField(DOT_CAPACITY, TRAIL_LENGTH, GENESIS_SEED_A, GENESIS_SEED_B, GENESIS_SEED_C);
        this.tickCount = 0;
    }

    public double getDriftScale() {
        return driftScale;
    }

    public int getCanvasWidth() {
        return canvasWidth;
    }

    public int getCanvasHeight() {
        return canvasHeight;
    }

    public long getConstructionTimeMs() {
        return constructionTimeMs;
    }

    public long getTickCount() {
        return tickCount;
    }

    public DotField getField() {
        return field;
    }

    public void addListener(DotMasterListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(DotMasterListener listener) {
        listeners.remove(listener);
    }

    private void fireTickEvent(long tick, DotField f) {
        DotMasterEvent ev = new DotMasterEvent(this, tick, f);
        for (DotMasterListener l : listeners) {
            l.onTick(ev);
        }
    }

    private void fireRenderEvent(BufferedImage frame) {
        for (DotMasterListener l : listeners) {
            l.onFrameRendered(frame);
        }
    }

    public void tick() {
        field = field.tick(driftScale, PHASE_SPEED, constructionTimeMs, tickCount, digest, DRIFT_ORACLE, TRAIL_HASH_SALT);
        tickCount++;
        fireTickEvent(tickCount, field);
    }

    public void tick(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    public BufferedImage renderFrame(boolean clearBackground, Color backgroundColor) {
        BufferedImage img = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            if (clearBackground && backgroundColor != null) {
                g.setColor(backgroundColor);
                g.fillRect(0, 0, canvasWidth, canvasHeight);
            }
            field.draw(g, canvasWidth, canvasHeight, PALETTE_ANCHOR, digest);
        } finally {
            g.dispose();
        }
        fireRenderEvent(img);
        return img;
    }

    public BufferedImage renderFrame() {
        return renderFrame(true, new Color(0x0a, 0x0a, 0x12, 255));
    }

    public byte[] exportFramePng(BufferedImage frame) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(frame, "png", out);
        return out.toByteArray();
    }

    public byte[] renderAndExportPng() throws IOException {
        BufferedImage frame = renderFrame();
        return exportFramePng(frame);
    }

    public static byte[] hash(MessageDigest digest, String... inputs) {
        digest.reset();
        for (String s : inputs) {
            if (s != null) {
                digest.update(s.getBytes(StandardCharsets.UTF_8));
            }
        }
        return digest.digest();
    }

    public static int hashToInt(byte[] hash, int offset) {
        if (hash == null || offset < 0 || offset + 4 > hash.length) {
            return 0;
        }
        return ((hash[offset] & 0xFF) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
    }

    public static double hashToUnit(byte[] hash, int offset) {
        int i = hashToInt(hash, offset);
        return (double) (i & 0x7FFFFFFF) / (double) Integer.MAX_VALUE;
    }

    public static Color hashToColor(byte[] hash, String paletteAnchor) {
        if (hash == null || hash.length < 12) {
            return new Color(128, 128, 200, 200);
        }
        int r = (hash[0] & 0xFF) ^ ((hashToInt(hash, 0) >> 16) & 0xFF);
        int g = (hash[4] & 0xFF) ^ ((hashToInt(hash, 4) >> 16) & 0xFF);
        int b = (hash[8] & 0xFF) ^ ((hashToInt(hash, 8) >> 16) & 0xFF);
        r = Math.max(32, Math.min(255, r));
        g = Math.max(32, Math.min(255, g));
        b = Math.max(32, Math.min(255, b));
        return new Color(r, g, b, 220);
    }

    // ---------------------------------------------------------------------------
    // Dot — single particle with position and trail
    // ---------------------------------------------------------------------------

    public static final class Dot {
        private final double x;
        private final double y;
        private final double phase;
        private final int index;
        private final double[] trailX;
        private final double[] trailY;
        private final int trailLength;
        private int trailHead;

        public Dot(double x, double y, double phase, int index, int trailLength) {
            this.x = x;
            this.y = y;
            this.phase = phase;
            this.index = index;
            this.trailLength = Math.max(1, trailLength);
            this.trailX = new double[this.trailLength];
            this.trailY = new double[this.trailLength];
            this.trailHead = 0;
            for (int i = 0; i < this.trailLength; i++) {
                trailX[i] = x;
                trailY[i] = y;
            }
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }
