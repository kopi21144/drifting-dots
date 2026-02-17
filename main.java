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

        public double getPhase() {
            return phase;
        }

        public int getIndex() {
            return index;
        }

        public int getTrailLength() {
            return trailLength;
        }

        public double getTrailX(int i) {
            if (i < 0 || i >= trailLength) return x;
            int j = (trailHead - 1 - i + trailLength * 2) % trailLength;
            return trailX[j];
        }

        public double getTrailY(int i) {
            if (i < 0 || i >= trailLength) return y;
            int j = (trailHead - 1 - i + trailLength * 2) % trailLength;
            return trailY[j];
        }

        public Dot withNewPosition(double newX, double newY, double newPhase) {
            Dot next = new Dot(newX, newY, newPhase, index, trailLength);
            for (int i = 0; i < trailLength; i++) {
                next.trailX[i] = this.trailX[i];
                next.trailY[i] = this.trailY[i];
            }
            next.trailHead = this.trailHead;
            next.trailX[next.trailHead] = newX;
            next.trailY[next.trailHead] = newY;
            next.trailHead = (next.trailHead + 1) % trailLength;
            return next;
        }
    }

    // ---------------------------------------------------------------------------
    // DotField — immutable snapshot of all dots
    // ---------------------------------------------------------------------------

    public static final class DotField {
        private final List<Dot> dots;
        private final String seedA;
        private final String seedB;
        private final String seedC;
        private final int trailLength;

        public DotField(int capacity, int trailLength, String seedA, String seedB, String seedC) {
            this.trailLength = Math.max(1, trailLength);
            this.seedA = seedA;
            this.seedB = seedB;
            this.seedC = seedC;
            this.dots = new ArrayList<>(capacity);
            for (int i = 0; i < capacity; i++) {
                double u = (double) i / (double) Math.max(1, capacity);
                double x = 0.2 + 0.6 * (0.5 + 0.5 * Math.sin(u * Math.PI * 4));
                double y = 0.2 + 0.6 * (0.5 + 0.5 * Math.cos(u * Math.PI * 3));
                double phase = u * Math.PI * 2;
                dots.add(new Dot(x, y, phase, i, this.trailLength));
            }
        }

        private DotField(List<Dot> dots, String seedA, String seedB, String seedC, int trailLength) {
            this.dots = new ArrayList<>(dots);
            this.seedA = seedA;
            this.seedB = seedB;
            this.seedC = seedC;
            this.trailLength = trailLength;
        }

        public List<Dot> getDots() {
            return new ArrayList<>(dots);
        }

        public int getDotCount() {
            return dots.size();
        }

        public DotField tick(double driftScale, double phaseSpeed, long constructionTimeMs, long tickCount,
                             MessageDigest digest, String driftOracle, String trailSalt) {
            List<Dot> nextDots = new ArrayList<>(dots.size());
            long t = constructionTimeMs + tickCount * 17L;
            for (Dot d : dots) {
                byte[] h = hash(digest, seedA, seedB, seedC, String.valueOf(d.getIndex()), String.valueOf(t), driftOracle);
                double dx = (hashToUnit(h, 0) - 0.5) * driftScale;
                double dy = (hashToUnit(h, 8) - 0.5) * driftScale;
                double newPhase = d.getPhase() + phaseSpeed * (hashToInt(h, 4) % 1000) / 1000.0;
                double newX = clamp01(d.getX() + dx);
                double newY = clamp01(d.getY() + dy);
                nextDots.add(d.withNewPosition(newX, newY, newPhase));
            }
            return new DotField(nextDots, seedA, seedB, seedC, trailLength);
        }

        public void draw(Graphics2D g, int width, int height, String paletteAnchor, MessageDigest digest) {
            for (Dot d : dots) {
                byte[] h = hash(digest, paletteAnchor, String.valueOf(d.getIndex()), String.valueOf(d.getPhase()));
                Color c = hashToColor(h, paletteAnchor);
                g.setColor(c);
                int px = (int) (d.getX() * width);
                int py = (int) (d.getY() * height);
                int radius = 2 + (hashToInt(h, 0) % 3);
                g.fillOval(px - radius, py - radius, radius * 2, radius * 2);
                for (int i = d.getTrailLength() - 1; i >= 0; i--) {
                    double tx = d.getTrailX(i);
                    double ty = d.getTrailY(i);
                    int trailAlpha = 80 - (i * 70 / Math.max(1, d.getTrailLength()));
                    if (trailAlpha < 5) trailAlpha = 5;
                    Color trailColor = new Color(c.getRed(), c.getGreen(), c.getBlue(), trailAlpha);
                    g.setColor(trailColor);
                    int txp = (int) (tx * width);
                    int typ = (int) (ty * height);
                    g.fillOval(txp - 1, typ - 1, 2, 2);
                }
            }
        }

        private static double clamp01(double v) {
            if (v < 0) return 0;
            if (v > 1) return 1;
            return v;
        }
    }

    // ---------------------------------------------------------------------------
    // DotMasterEvent — immutable event payload
    // ---------------------------------------------------------------------------

    public static final class DotMasterEvent {
        private final DriftingDots source;
        private final long tick;
        private final DotField field;

        public DotMasterEvent(DriftingDots source, long tick, DotField field) {
            this.source = source;
            this.tick = tick;
            this.field = field;
        }

        public DriftingDots getSource() {
            return source;
        }

        public long getTick() {
            return tick;
        }

        public DotField getField() {
            return field;
        }
    }

    // ---------------------------------------------------------------------------
    // DotMasterListener — callback interface
    // ---------------------------------------------------------------------------

    public interface DotMasterListener {
        void onTick(DotMasterEvent event);
        void onFrameRendered(BufferedImage frame);
    }

    // ---------------------------------------------------------------------------
    // Preset configurations (all final / immutable-style)
    // ---------------------------------------------------------------------------

    public static final class Presets {
        public static final double DRIFT_CALM = 0.0002;
        public static final double DRIFT_MEDIUM = 0.0004127;
        public static final double DRIFT_WILD = 0.0012;
        public static final int SIZE_SMALL_W = 800;
        public static final int SIZE_SMALL_H = 600;
        public static final int SIZE_HD_W = 1920;
        public static final int SIZE_HD_H = 1080;
        public static final int SIZE_4K_W = 3840;
        public static final int SIZE_4K_H = 2160;

        private Presets() {
        }

        public static DriftingDots createCalm() {
            return new DriftingDots(DRIFT_CALM, SIZE_HD_W, SIZE_HD_H);
        }

        public static DriftingDots createMedium() {
            return new DriftingDots(DRIFT_MEDIUM, SIZE_HD_W, SIZE_HD_H);
        }

        public static DriftingDots createWild() {
            return new DriftingDots(DRIFT_WILD, SIZE_HD_W, SIZE_HD_H);
        }

        public static DriftingDots createSmall() {
            return new DriftingDots(DRIFT_MEDIUM, SIZE_SMALL_W, SIZE_SMALL_H);
        }

        public static DriftingDots create4K() {
            return new DriftingDots(DRIFT_CALM, SIZE_4K_W, SIZE_4K_H);
        }
    }

    // ---------------------------------------------------------------------------
    // Export formats and validation
    // ---------------------------------------------------------------------------

    public byte[] export(int format) throws IOException {
        if (format == EXPORT_FORMAT_PNG) {
            return renderAndExportPng();
        }
        if (format == EXPORT_FORMAT_RAW) {
            return exportRawDotData();
        }
        throw new IllegalArgumentException("DriftingDots: unknown export format " + format);
    }

    private byte[] exportRawDotData() {
        StringBuilder sb = new StringBuilder();
        sb.append("DriftingDots-Raw-v1\n");
        sb.append("tick=").append(tickCount).append("\n");
        sb.append("dots=").append(field.getDotCount()).append("\n");
        for (Dot d : field.getDots()) {
            sb.append(String.format("%d %.6f %.6f %.6f\n", d.getIndex(), d.getX(), d.getY(), d.getPhase()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public void validateState() {
        if (field == null) {
            throw new IllegalStateException("DriftingDots: field is null");
        }
        if (field.getDotCount() > DOT_CAPACITY) {
            throw new IllegalStateException("DriftingDots: dot count exceeds capacity");
        }
        if (tickCount < 0) {
            throw new IllegalStateException("DriftingDots: tick count negative");
        }
    }

    public String getEngineVersion() {
        return ENGINE_VERSION;
    }

    public String getGenesisFingerprint() {
        return String.format("%s-%s-%s-%d",
                GENESIS_SEED_A.substring(0, 10),
                GENESIS_SEED_B.substring(0, 10),
                GENESIS_SEED_C.substring(0, 10),
                constructionTimeMs
        );
    }

    // ---------------------------------------------------------------------------
    // Batch runner for headless animation export
    // ---------------------------------------------------------------------------

    public static class BatchRunner {
        private final DriftingDots engine;
        private final int frames;
        private final int ticksPerFrame;
        private final String outputPathPrefix;

        public BatchRunner(DriftingDots engine, int frames, int ticksPerFrame, String outputPathPrefix) {
            this.engine = engine;
            this.frames = Math.max(1, frames);
            this.ticksPerFrame = Math.max(1, ticksPerFrame);
            this.outputPathPrefix = outputPathPrefix != null ? outputPathPrefix : "frame";
        }

        public int getFrames() {
            return frames;
        }

        public int getTicksPerFrame() {
            return ticksPerFrame;
        }

        public void run() throws IOException {
            for (int f = 0; f < frames; f++) {
                engine.tick(ticksPerFrame);
                BufferedImage img = engine.renderFrame();
                String path = outputPathPrefix + "_" + String.format("%05d", f) + ".png";
                ImageIO.write(img, "png", new java.io.File(path));
            }
        }

        public List<BufferedImage> runInMemory() {
            List<BufferedImage> list = new ArrayList<>(frames);
            for (int f = 0; f < frames; f++) {
                engine.tick(ticksPerFrame);
                list.add(engine.renderFrame());
            }
            return list;
        }
    }

    // ---------------------------------------------------------------------------
    // Color palette derived from PALETTE_ANCHOR (deterministic)
    // ---------------------------------------------------------------------------

    public static final class Palette {
        public static final int PALETTE_SIZE = 16;
        private final Color[] colors;

        public Palette(MessageDigest digest, String anchor) {
            this.colors = new Color[PALETTE_SIZE];
            for (int i = 0; i < PALETTE_SIZE; i++) {
                byte[] h = hash(digest, anchor, String.valueOf(i));
                colors[i] = hashToColor(h, anchor);
            }
        }

        public Color get(int index) {
            if (index < 0 || index >= PALETTE_SIZE) {
                return colors[0];
            }
            return colors[index];
        }

        public int size() {
            return PALETTE_SIZE;
        }
    }

    // ---------------------------------------------------------------------------
    // RenderMode — how dots are drawn (names unique to this engine)
    // ---------------------------------------------------------------------------

    public enum RenderMode {
        SOLID_CORE,
        TRAIL_ONLY,
        CORE_AND_TRAIL,
        PULSE_RINGS,
        GRADIENT_FADE
    }

    // ---------------------------------------------------------------------------
    // Advanced renderer with mode selection
    // ---------------------------------------------------------------------------

    public static final class DotMasterRenderer {
        private final int width;
        private final int height;
        private final RenderMode mode;
        private final MessageDigest digest;
        private final String paletteAnchor;

        public DotMasterRenderer(int width, int height, RenderMode mode, MessageDigest digest, String paletteAnchor) {
            this.width = width;
            this.height = height;
            this.mode = mode;
            this.digest = digest;
            this.paletteAnchor = paletteAnchor;
        }

        public BufferedImage render(DotField field, Color background) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                if (background != null) {
                    g.setColor(background);
                    g.fillRect(0, 0, width, height);
                }
                switch (mode) {
                    case SOLID_CORE:
                        renderSolidCore(g, field);
                        break;
                    case TRAIL_ONLY:
                        renderTrailOnly(g, field);
                        break;
                    case CORE_AND_TRAIL:
                        renderCoreAndTrail(g, field);
                        break;
                    case PULSE_RINGS:
                        renderPulseRings(g, field);
                        break;
                    case GRADIENT_FADE:
                        renderGradientFade(g, field);
                        break;
                    default:
                        renderCoreAndTrail(g, field);
                }
            } finally {
                g.dispose();
            }
            return img;
        }

        private void renderSolidCore(Graphics2D g, DotField field) {
            for (Dot d : field.getDots()) {
                byte[] h = hash(digest, paletteAnchor, String.valueOf(d.getIndex()));
                Color c = hashToColor(h, paletteAnchor);
                g.setColor(c);
                int px = (int) (d.getX() * width);
                int py = (int) (d.getY() * height);
                int r = 3 + (hashToInt(h, 0) % 4);
                g.fillOval(px - r, py - r, r * 2, r * 2);
            }
        }

        private void renderTrailOnly(Graphics2D g, DotField field) {
            for (Dot d : field.getDots()) {
                byte[] h = hash(digest, paletteAnchor, String.valueOf(d.getIndex()));
                Color c = hashToColor(h, paletteAnchor);
                for (int i = d.getTrailLength() - 1; i >= 0; i--) {
                    int alpha = 255 - (i * 250 / Math.max(1, d.getTrailLength()));
                    if (alpha < 10) alpha = 10;
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
                    double tx = d.getTrailX(i);
                    double ty = d.getTrailY(i);
                    int rad = 1 + (i % 2);
                    g.fillOval((int) (tx * width) - rad, (int) (ty * height) - rad, rad * 2, rad * 2);
                }
            }
        }

        private void renderCoreAndTrail(Graphics2D g, DotField field) {
            field.draw(g, width, height, paletteAnchor, digest);
        }

        private void renderPulseRings(Graphics2D g, DotField field) {
            for (Dot d : field.getDots()) {
                byte[] h = hash(digest, paletteAnchor, String.valueOf(d.getIndex()), String.valueOf(d.getPhase()));
                Color c = hashToColor(h, paletteAnchor);
                int px = (int) (d.getX() * width);
                int py = (int) (d.getY() * height);
                for (int ring = 3; ring >= 0; ring--) {
                    int alpha = 60 - ring * 15;
                    if (alpha < 5) alpha = 5;
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
                    int rad = 8 + ring * 6 + (hashToInt(h, ring * 2) % 4);
                    g.drawOval(px - rad, py - rad, rad * 2, rad * 2);
                }
                g.setColor(c);
                g.fillOval(px - 2, py - 2, 4, 4);
            }
        }

        private void renderGradientFade(Graphics2D g, DotField field) {
            for (Dot d : field.getDots()) {
                byte[] h = hash(digest, paletteAnchor, String.valueOf(d.getIndex()));
                for (int i = d.getTrailLength() - 1; i >= 0; i--) {
                    double t = (double) i / Math.max(1, d.getTrailLength());
                    int r = (int) (hashToInt(h, 0) % 256);
                    int gr = (int) (hashToInt(h, 4) % 256);
                    int b = (int) (hashToInt(h, 8) % 256);
                    int alpha = (int) (220 * (1.0 - t));
                    if (alpha < 5) alpha = 5;
                    g.setColor(new Color(r, gr, b, alpha));
                    int txp = (int) (d.getTrailX(i) * width);
                    int typ = (int) (d.getTrailY(i) * height);
                    g.fillOval(txp - 2, typ - 2, 4, 4);
                }
                Color c = hashToColor(h, paletteAnchor);
                g.setColor(c);
                g.fillOval((int) (d.getX() * width) - 3, (int) (d.getY() * height) - 3, 6, 6);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Timeline — fixed keyframes for deterministic replay
    // ---------------------------------------------------------------------------

    public static final class Timeline {
        private final List<Long> keyframeTicks = new ArrayList<>();
        private final List<Double> keyframeDriftMultipliers = new ArrayList<>();
        private static final int MAX_KEYFRAMES = 128;

        public Timeline() {
        }

        public void addKeyframe(long tick, double driftMultiplier) {
            if (keyframeTicks.size() >= MAX_KEYFRAMES) {
                throw new IllegalStateException("DriftingDots: timeline keyframe cap reached");
            }
            keyframeTicks.add(tick);
            keyframeDriftMultipliers.add(driftMultiplier);
        }

        public double getDriftMultiplierAt(long tick) {
            if (keyframeTicks.isEmpty()) return 1.0;
            int i = 0;
            while (i < keyframeTicks.size() && keyframeTicks.get(i) <= tick) i++;
            if (i == 0) return keyframeDriftMultipliers.get(0);
            if (i >= keyframeTicks.size()) return keyframeDriftMultipliers.get(keyframeDriftMultipliers.size() - 1);
            long t0 = keyframeTicks.get(i - 1);
            long t1 = keyframeTicks.get(i);
            double m0 = keyframeDriftMultipliers.get(i - 1);
            double m1 = keyframeDriftMultipliers.get(i);
            double frac = (double) (tick - t0) / (double) Math.max(1, t1 - t0);
            return m0 + frac * (m1 - m0);
        }

        public int getKeyframeCount() {
            return keyframeTicks.size();
        }
    }

    // ---------------------------------------------------------------------------
    // SeedDeriver — deterministic sub-seeds from master seeds (unique naming)
    // ---------------------------------------------------------------------------

    public static final class SeedDeriver {
        private final MessageDigest digest;
        private final String masterA;
        private final String masterB;

        public SeedDeriver(MessageDigest digest, String masterA, String masterB) {
            this.digest = digest;
            this.masterA = masterA;
            this.masterB = masterB;
        }

        public byte[] derive(String label, long nonce) {
            return hash(digest, masterA, masterB, label, String.valueOf(nonce));
        }

        public double deriveUnit(String label, long nonce, int byteOffset) {
            byte[] h = derive(label, nonce);
            return hashToUnit(h, byteOffset % Math.max(1, h.length - 4));
        }

        public int deriveInt(String label, long nonce, int bound) {
            byte[] h = derive(label, nonce);
            int v = hashToInt(h, 0) & 0x7FFFFFFF;
            return bound <= 0 ? 0 : (v % bound);
        }
    }

    // ---------------------------------------------------------------------------
    // Bounds checker — safe for mainnet-style invariants
    // ---------------------------------------------------------------------------

    public static final class Bounds {
        public static final double MIN_DRIFT = 0.00001;
        public static final double MAX_DRIFT = 0.01;
        public static final int MIN_CANVAS = 64;
        public static final int MAX_CANVAS = 16384;
        public static final int MIN_DOTS = 1;
        public static final int MAX_DOTS = 8192;

        public static void checkDrift(double v) {
            if (v < MIN_DRIFT || v > MAX_DRIFT) {
                throw new IllegalArgumentException("DriftingDots: drift out of bounds");
            }
        }

        public static void checkCanvas(int w, int h) {
            if (w < MIN_CANVAS || h < MIN_CANVAS || w > MAX_CANVAS || h > MAX_CANVAS) {
                throw new IllegalArgumentException("DriftingDots: canvas dimensions out of bounds");
            }
        }

        public static void checkDotCount(int n) {
            if (n < MIN_DOTS || n > MAX_DOTS) {
                throw new IllegalArgumentException("DriftingDots: dot count out of bounds");
            }
        }
    }

    // ---------------------------------------------------------------------------
    // FrameSequence — in-memory frame buffer for export
    // ---------------------------------------------------------------------------

    public static final class FrameSequence {
        private final List<BufferedImage> frames = new ArrayList<>();
        private static final int MAX_FRAMES = 1024;

        public void add(BufferedImage frame) {
            if (frames.size() >= MAX_FRAMES) {
                throw new IllegalStateException("DriftingDots: frame sequence cap reached");
            }
            frames.add(frame);
        }

        public int size() {
            return frames.size();
        }

        public BufferedImage get(int index) {
            return frames.get(index);
        }

        public void writeToDirectory(String pathPrefix) throws IOException {
            java.io.File dir = new java.io.File(pathPrefix).getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            for (int i = 0; i < frames.size(); i++) {
                String path = pathPrefix + "_" + String.format("%05d", i) + ".png";
                ImageIO.write(frames.get(i), "png", new java.io.File(path));
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Stats — read-only snapshot of engine stats (no "readonly" keyword; use final)
    // ---------------------------------------------------------------------------

    public static final class EngineStats {
        private final long tickCount;
        private final int dotCount;
        private final int width;
        private final int height;
        private final String fingerprint;

        public EngineStats(DriftingDots engine) {
            this.tickCount = engine.getTickCount();
            this.dotCount = engine.getField().getDotCount();
            this.width = engine.getCanvasWidth();
            this.height = engine.getCanvasHeight();
            this.fingerprint = engine.getGenesisFingerprint();
        }

        public long getTickCount() {
            return tickCount;
        }

        public int getDotCount() {
            return dotCount;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        @Override
        public String toString() {
            return String.format("EngineStats{tick=%d dots=%d %dx%d fingerprint=%s}",
                    tickCount, dotCount, width, height, fingerprint);
        }
    }

    // ---------------------------------------------------------------------------
    // Default listener implementation (no-op for optional overrides)
    // ---------------------------------------------------------------------------

    public static class DotMasterListenerAdapter implements DotMasterListener {
        @Override
        public void onTick(DotMasterEvent event) {
        }

        @Override
        public void onFrameRendered(BufferedImage frame) {
        }
    }

    // ---------------------------------------------------------------------------
    // Factory with validation (mainnet-safe defaults)
    // ---------------------------------------------------------------------------
