package com.rstms.video;

import com.rstms.model.ScreenRegionSpec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The "Rendu TV" math (see TvLookSpec): what the camera does to a picture shown on a TV.
 *
 * <p>Two independent parts:
 * <ul>
 *   <li><b>Colour</b> - brightness, contrast, saturation, temperature and the panel's black level,
 *   composed into ONE affine map in sRGB (a 3x4 matrix), clamped once at the end. The render applies
 *   it as a 3D LUT (lut3d - tetrahedral interpolation reproduces an affine map exactly); the preview
 *   as an SVG feColorMatrix with the very same numbers.</li>
 *   <li><b>Glass</b> - edge darkening + panel lines (one "darkening" mask) and a light reflection
 *   (one "glare" mask), both static and computed here once per job. They follow the TV frame's
 *   perspective through its homography (the same square-to-quad mapping the perspective warp uses):
 *   (u,v) in [0,1]^2 is the position ON the screen, whatever angle it is filmed at.</li>
 * </ul>
 *
 * <p>The wizard's live preview re-implements these formulas in JavaScript (tvLookColorMatrix,
 * tvLookGeometry, tvLookDarkAlpha, tvLookGlareAlpha and the TVLOOK constants in index.html) - any
 * change here MUST be mirrored there. The two are compared numerically whenever either side changes.
 */
public final class TvLook {

    /** Luma weights of the saturation step (BT.709, the same the CSS/SVG saturate matrix uses). */
    public static final double LUMA_R = 0.2126, LUMA_G = 0.7152, LUMA_B = 0.0722;
    /** Temperature +/-1 scales red and blue by +/-10% in opposite directions. */
    public static final double TEMPERATURE_GAIN = 0.1;
    /** Edge darkening: vignette * VIGNETTE_MAX * r2^VIGNETTE_EXPONENT, r2 = 0 at the centre, 1 at a corner. */
    public static final double VIGNETTE_MAX = 0.55;
    public static final double VIGNETTE_EXPONENT = 1.25;
    /** Panel lines: a smooth sinusoid (never a hard line, so it doesn't alias), one period per
     * LINE_PITCH_PX of the screen's height, darkening at most LINE_MAX at full strength. */
    public static final double LINE_PITCH_PX = 3.0;
    public static final double LINE_MAX = 0.45;
    /** Reflection: a soft band across a GLARE_ANGLE_DEG diagonal, fading towards the bottom, plus a
     * faint sheen over the top of the glass. */
    public static final double GLARE_ANGLE_DEG = 35.0;
    public static final double GLARE_WIDTH = 0.11;
    public static final double GLARE_BAND_MAX = 0.35;
    public static final double GLARE_FADE = 0.6;
    public static final double GLARE_SHEEN_MAX = 0.08;
    /** Points per axis of the colour LUT. */
    public static final int LUT_SIZE = 33;

    private TvLook() { }

    // ---- colour -------------------------------------------------------------------------------

    /**
     * The colour map as a row-major 3x4 matrix: out_c = m[c][0]*r + m[c][1]*g + m[c][2]*b + m[c][3],
     * in [0,1] sRGB, applied as (in this order) brightness v*B, contrast (v-0.5)*C+0.5, saturation
     * L+S*(v-L), temperature (red x(1+0.1T), blue x(1-0.1T)), black level K+v*(1-K).
     */
    public static double[][] colorMatrix(double brightness, double contrast, double saturation,
                                         double temperature, double blackLevel) {
        double[][] m = identity();
        m = then(m, scale(brightness, brightness, brightness), 0);
        m = then(m, scale(contrast, contrast, contrast), 0.5 * (1 - contrast));
        double s = saturation;
        double[][] sat = {
                {s + (1 - s) * LUMA_R, (1 - s) * LUMA_G, (1 - s) * LUMA_B},
                {(1 - s) * LUMA_R, s + (1 - s) * LUMA_G, (1 - s) * LUMA_B},
                {(1 - s) * LUMA_R, (1 - s) * LUMA_G, s + (1 - s) * LUMA_B}};
        m = then(m, sat, 0);
        m = then(m, scale(1 + TEMPERATURE_GAIN * temperature, 1, 1 - TEMPERATURE_GAIN * temperature), 0);
        m = then(m, scale(1 - blackLevel, 1 - blackLevel, 1 - blackLevel), blackLevel);
        return m;
    }

    private static double[][] identity() {
        return new double[][]{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}};
    }

    private static double[][] scale(double r, double g, double b) {
        return new double[][]{{r, 0, 0}, {0, g, 0}, {0, 0, b}};
    }

    /** Composes "m, then the linear part a plus the same offset on every channel". */
    private static double[][] then(double[][] m, double[][] a, double offset) {
        double[][] out = new double[3][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 4; j++) {
                double v = 0;
                for (int k = 0; k < 3; k++) v += a[i][k] * m[k][j];
                out[i][j] = v;
            }
            out[i][3] += offset;
        }
        return out;
    }

    public static boolean isIdentity(double[][] m) {
        double[][] id = identity();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 4; j++) {
                if (Math.abs(m[i][j] - id[i][j]) > 1e-9) return false;
            }
        }
        return true;
    }

    /** The colour map applied to one [0,1] sRGB colour, clamped. */
    public static double[] applyColor(double[][] m, double r, double g, double b) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.max(0, Math.min(1, m[i][0] * r + m[i][1] * g + m[i][2] * b + m[i][3]));
        }
        return out;
    }

    /** Half an 8-bit code added to every LUT output: ffmpeg's lut3d converts its float result to an
     * integer by truncation, which on its own darkens everything by 0.5 level on average (measured on
     * the user's footage: -0.50 before, +0.001 after). */
    public static final double LUT_ROUNDING_BIAS = 0.5 / 255;

    /** Writes the colour map as a LUT_SIZE^3 .cube file (red index varying fastest, as the format
     * and ffmpeg's lut3d parser expect), each output raised by LUT_ROUNDING_BIAS. */
    public static void writeCubeLut(double[][] m, Path target) throws IOException {
        int n = LUT_SIZE;
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.US_ASCII)) {
            w.write("TITLE \"rstms tv look\"\n");
            w.write("LUT_3D_SIZE " + n + "\n");
            w.write("DOMAIN_MIN 0.0 0.0 0.0\n");
            w.write("DOMAIN_MAX 1.0 1.0 1.0\n");
            for (int bi = 0; bi < n; bi++) {
                for (int gi = 0; gi < n; gi++) {
                    for (int ri = 0; ri < n; ri++) {
                        double[] c = applyColor(m, ri / (double) (n - 1), gi / (double) (n - 1), bi / (double) (n - 1));
                        w.write(String.format(Locale.ROOT, "%.6f %.6f %.6f\n",
                                c[0] + LUT_ROUNDING_BIAS, c[1] + LUT_ROUNDING_BIAS, c[2] + LUT_ROUNDING_BIAS));
                    }
                }
            }
        }
    }

    // ---- glass (geometry + masks) -------------------------------------------------------------

    /** The TV frame's projective mapping and the pixel box its masks cover. */
    public static final class Geometry {
        /** Forward map (u,v) -> (x,y) as {a,b,c,d,e,f,g,h}: x = (a u + b v + c) / (g u + h v + 1),
         * y = (d u + e v + f) / (same). */
        final double[] fwd;
        /** Inverse, as a row-major 3x3 matrix applied to (x, y, 1). */
        final double[] inv;
        /** Number of panel lines across the screen's height (see LINE_PITCH_PX). */
        public final double lineCount;
        /** The frame's bounding box in frame px (even w/h, for yuv420p layers). */
        public final int x, y, w, h;

        private Geometry(double[] fwd, double[] inv, double lineCount, int x, int y, int w, int h) {
            this.fwd = fwd; this.inv = inv; this.lineCount = lineCount;
            this.x = x; this.y = y; this.w = w; this.h = h;
        }

        public boolean isDegenerate() { return inv == null; }
    }

    /** Heckbert's square-to-quad mapping of the frame (TL=(0,0), TR=(1,0), BR=(1,1), BL=(0,1)) - the
     * same algorithm as the wizard's quadTransformMatrix3d. */
    public static Geometry geometry(ScreenRegionSpec frame) {
        double x0 = frame.getTopLeft().getX(), y0 = frame.getTopLeft().getY();
        double x1 = frame.getTopRight().getX(), y1 = frame.getTopRight().getY();
        double x2 = frame.getBottomRight().getX(), y2 = frame.getBottomRight().getY();
        double x3 = frame.getBottomLeft().getX(), y3 = frame.getBottomLeft().getY();
        double dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
        double dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
        double a, b, c, d, e, f, g, h;
        if (Math.abs(dx3) < 1e-9 && Math.abs(dy3) < 1e-9) {
            a = x1 - x0; b = x2 - x1; c = x0;
            d = y1 - y0; e = y2 - y1; f = y0;
            g = 0; h = 0;
        } else {
            double den = dx1 * dy2 - dx2 * dy1;
            if (Math.abs(den) < 1e-12) den = 1e-12;
            g = (dx3 * dy2 - dx2 * dy3) / den;
            h = (dx1 * dy3 - dx3 * dy1) / den;
            a = x1 - x0 + g * x1; b = x3 - x0 + h * x3; c = x0;
            d = y1 - y0 + g * y1; e = y3 - y0 + h * y3; f = y0;
        }
        double[] inv = invert3(new double[]{a, b, c, d, e, f, g, h, 1});

        double minX = Math.min(Math.min(x0, x1), Math.min(x2, x3)), maxX = Math.max(Math.max(x0, x1), Math.max(x2, x3));
        double minY = Math.min(Math.min(y0, y1), Math.min(y2, y3)), maxY = Math.max(Math.max(y0, y1), Math.max(y2, y3));
        // Even origin (the box grows by a pixel instead): ffmpeg's overlay rounds an odd x/y down to
        // even on yuv420p, which would put the glass one pixel up/left of where it is computed.
        int bx = Math.floorDiv((int) Math.floor(minX), 2) * 2, by = Math.floorDiv((int) Math.floor(minY), 2) * 2;
        int bw = Math.max(2, (int) Math.ceil(maxX) - bx), bh = Math.max(2, (int) Math.ceil(maxY) - by);
        if (bw % 2 != 0) bw++;
        if (bh % 2 != 0) bh++;
        double height = (Math.hypot(x3 - x0, y3 - y0) + Math.hypot(x2 - x1, y2 - y1)) / 2;
        double lines = Math.max(1, Math.round(height / LINE_PITCH_PX));
        return new Geometry(new double[]{a, b, c, d, e, f, g, h}, inv, lines, bx, by, bw, bh);
    }

    /** Inverse of a row-major 3x3 matrix, or null when it is (near) singular - a collapsed frame. */
    private static double[] invert3(double[] m) {
        double c00 = m[4] * m[8] - m[5] * m[7], c01 = m[5] * m[6] - m[3] * m[8], c02 = m[3] * m[7] - m[4] * m[6];
        double det = m[0] * c00 + m[1] * c01 + m[2] * c02;
        if (!Double.isFinite(det) || Math.abs(det) < 1e-9) return null;
        return new double[]{
                c00 / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
                c01 / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                c02 / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det};
    }

    /** Frame point for a screen position (u,v) - used by the tests. */
    public static double[] toFrame(Geometry geo, double u, double v) {
        double[] p = geo.fwd;
        double den = p[6] * u + p[7] * v + 1;
        return new double[]{(p[0] * u + p[1] * v + p[2]) / den, (p[3] * u + p[4] * v + p[5]) / den};
    }

    /** Screen position (u,v) of a frame point, or null when the point is not on the screen. The
     * mapping is a bijection, so the unit square's preimage is exactly the frame's inside. */
    public static double[] toScreen(Geometry geo, double px, double py) {
        if (geo.inv == null) return null;
        double[] m = geo.inv;
        double wq = m[6] * px + m[7] * py + m[8];
        if (Math.abs(wq) < 1e-12) return null;
        double u = (m[0] * px + m[1] * py + m[2]) / wq;
        double v = (m[3] * px + m[4] * py + m[5]) / wq;
        if (!(u >= 0 && u <= 1 && v >= 0 && v <= 1)) return null;
        return new double[]{u, v};
    }

    /** Darkening opacity 0..1 at screen position (u,v): edge vignette combined with the panel lines. */
    public static double darkAlpha(double u, double v, double lineCount, double vignette, double scanlines) {
        double r2 = ((2 * u - 1) * (2 * u - 1) + (2 * v - 1) * (2 * v - 1)) / 2;
        double vig = vignette * VIGNETTE_MAX * Math.pow(r2, VIGNETTE_EXPONENT);
        double line = scanlines * LINE_MAX * (0.5 - 0.5 * Math.cos(2 * Math.PI * v * lineCount));
        return Math.max(0, Math.min(1, 1 - (1 - vig) * (1 - line)));
    }

    /** Reflection opacity 0..1 (of white) at screen position (u,v). */
    public static double glareAlpha(double u, double v, double glare, double position) {
        double t = Math.toRadians(GLARE_ANGLE_DEG);
        double p = (u * Math.cos(t) + v * Math.sin(t)) / (Math.cos(t) + Math.sin(t));
        double q = (p - position) / GLARE_WIDTH;
        double band = Math.exp(-q * q);
        double a = glare * (GLARE_BAND_MAX * band * (1 - GLARE_FADE * v) + GLARE_SHEEN_MAX * (1 - v) * (1 - v));
        return Math.max(0, Math.min(1, a));
    }

    /** One byte (0..255) per pixel of the geometry's box, row-major, sampled at pixel centres; 0 off the screen. */
    public static byte[] darkMask(Geometry geo, double vignette, double scanlines) {
        byte[] out = new byte[geo.w * geo.h];
        for (int j = 0; j < geo.h; j++) {
            for (int i = 0; i < geo.w; i++) {
                double[] uv = toScreen(geo, geo.x + i + 0.5, geo.y + j + 0.5);
                if (uv != null) out[j * geo.w + i] = (byte) Math.round(255 * darkAlpha(uv[0], uv[1], geo.lineCount, vignette, scanlines));
            }
        }
        return out;
    }

    public static byte[] glareMask(Geometry geo, double glare, double position) {
        byte[] out = new byte[geo.w * geo.h];
        for (int j = 0; j < geo.h; j++) {
            for (int i = 0; i < geo.w; i++) {
                double[] uv = toScreen(geo, geo.x + i + 0.5, geo.y + j + 0.5);
                if (uv != null) out[j * geo.w + i] = (byte) Math.round(255 * glareAlpha(uv[0], uv[1], glare, position));
            }
        }
        return out;
    }
}
