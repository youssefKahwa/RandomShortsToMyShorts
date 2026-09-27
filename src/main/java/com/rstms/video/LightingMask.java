package com.rstms.video;

import com.rstms.model.EllipseZoneSpec;
import com.rstms.model.ScreenRegionSpec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The exact, deterministic shape of one screen's ambient "TV backlight" light and shadow.
 *
 * <p>Geometry (all in the delivered frame's own pixel space): the light is never drawn on the TV.
 * The TV is the user-placed light frame (the TV's outer outline, including its black border; "the
 * hole"), independent of the box the overlay video is placed in. The light starts exactly at the
 * hole's edge and fades to nothing over {@code reach} px, measured as the true distance from that
 * edge (so it is brightest right at the TV's border, like real bias lighting, and its shape follows
 * the TV - skewed frames included - instead of an ellipse around a box).
 *
 * <p>The wizard's live preview re-implements these same formulas in JavaScript (lightingShape,
 * lightingDistance, lightingGlowAlpha, lightingShadowAlpha and the LIGHT constants in index.html) - any
 * change here MUST be mirrored there, or preview and final render drift apart again. The two are
 * compared numerically (same alpha values on the same pixels) whenever either side changes.
 */
public final class LightingMask {

    /** Width of the soft ramp just outside the hole's edge (0 at the edge, full strength this many
     * px out) - keeps the light from starting as a razor-hard line and makes it forgiving of a
     * pixel or two of error in the placed frame. */
    public static final double EDGE_RAMP_PX = 3.0;
    /** Peak opacity of the glow right at the hole's edge: EDGE_BASE + EDGE_PER_BRIGHTNESS * brightness. */
    public static final double GLOW_EDGE_BASE = 0.15;
    public static final double GLOW_EDGE_PER_BRIGHTNESS = 0.55;
    /** Falloff exponent (light = (1-d/reach)^k): softness 0 -> tight (GLOW_K_MAX), 100 -> wide (GLOW_K_MIN). */
    public static final double GLOW_K_MAX = 2.6;
    public static final double GLOW_K_MIN = 1.4;
    /** The shadow reaches a bit less far than the glow, falls off faster, and never gets fully black. */
    public static final double SHADOW_REACH_FACTOR = 0.75;
    public static final double SHADOW_EXPONENT = 2.2;
    public static final double SHADOW_MAX_ALPHA = 0.6;
    /** Soft edge (px) of a no-light zone: the light fades from full to none over this distance. */
    public static final double OCCLUDER_FEATHER_PX = 4.0;

    private LightingMask() { }

    /** Geometry shared by the glow mask, the shadow mask and the filter-graph placement. */
    public static final class Shape {
        /** The hole = the light frame's corners, as TL,TR,BR,BL. */
        public final double[] hx = new double[4];
        public final double[] hy = new double[4];
        /** How far (px) past the hole's edge the glow reaches. */
        public final double reach;
        /** The lit box in frame px (may extend past the frame - overlay clips it). Even w/h (yuv420p). */
        public final int x, y, w, h;

        Shape(double reach, int x, int y, int w, int h) {
            this.reach = reach; this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    /** @param frame the TV's outer outline (see LightingEffectSpec.frame), in delivered-frame px. */
    public static Shape shape(ScreenRegionSpec frame, double spread) {
        double[] hx = {frame.getTopLeft().getX(), frame.getTopRight().getX(), frame.getBottomRight().getX(), frame.getBottomLeft().getX()};
        double[] hy = {frame.getTopLeft().getY(), frame.getTopRight().getY(), frame.getBottomRight().getY(), frame.getBottomLeft().getY()};

        double minX = Math.min(Math.min(hx[0], hx[1]), Math.min(hx[2], hx[3]));
        double maxX = Math.max(Math.max(hx[0], hx[1]), Math.max(hx[2], hx[3]));
        double minY = Math.min(Math.min(hy[0], hy[1]), Math.min(hy[2], hy[3]));
        double maxY = Math.max(Math.max(hy[0], hy[1]), Math.max(hy[2], hy[3]));
        double reach = Math.max(0, spread - 1) * 0.5 * ((maxX - minX) + (maxY - minY)) / 2.0;

        // Even origin: ffmpeg's overlay rounds an odd x/y down to even on yuv420p, which would draw the
        // light one pixel up/left of where it is computed (the box grows by that pixel instead).
        int x0 = Math.floorDiv((int) Math.floor(minX - reach), 2) * 2;
        int y0 = Math.floorDiv((int) Math.floor(minY - reach), 2) * 2;
        int x1 = (int) Math.ceil(maxX + reach);
        int y1 = (int) Math.ceil(maxY + reach);
        int w = Math.max(2, x1 - x0);
        int h = Math.max(2, y1 - y0);
        if (w % 2 != 0) w++;
        if (h % 2 != 0) h++;
        Shape s = new Shape(reach, x0, y0, w, h);
        System.arraycopy(hx, 0, s.hx, 0, 4);
        System.arraycopy(hy, 0, s.hy, 0, 4);
        return s;
    }

    /** Distance (px) from a point to the hole polygon; 0 when the point is inside it. */
    public static double distanceOutside(Shape s, double px, double py) {
        return polygonDistance(s.hx, s.hy, px, py);
    }

    /** Distance (px) from a point to a quad given as corner arrays; 0 when the point is inside it. */
    private static double polygonDistance(double[] qx, double[] qy, double px, double py) {
        if (inside(qx, qy, px, py)) return 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            best = Math.min(best, segmentDistance(px, py, qx[i], qy[i], qx[j], qy[j]));
        }
        return best;
    }

    private static boolean inside(double[] qx, double[] qy, double px, double py) {
        boolean in = false;
        for (int i = 0, j = 3; i < 4; j = i++) {
            if ((qy[i] > py) != (qy[j] > py)
                    && px < (qx[j] - qx[i]) * (py - qy[i]) / (qy[j] - qy[i]) + qx[i]) {
                in = !in;
            }
        }
        return in;
    }

    private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double ex = bx - ax, ey = by - ay;
        double len2 = ex * ex + ey * ey;
        double t = len2 < 1e-12 ? 0 : ((px - ax) * ex + (py - ay) * ey) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * ex), py - (ay + t * ey));
    }

    /** The no-light zones of one screen, in delivered-frame px: boxes (quads) and round zones
     * (axis-aligned ellipses {cx, cy, rx, ry}). */
    public static final class Zones {
        final double[][][] quads;
        final double[][] ellipses;

        private Zones(double[][][] quads, double[][] ellipses) { this.quads = quads; this.ellipses = ellipses; }

        public static Zones none() { return new Zones(new double[0][][], new double[0][]); }

        public static Zones of(List<ScreenRegionSpec> quads, List<EllipseZoneSpec> ellipses) {
            List<ScreenRegionSpec> q = quads != null ? quads : List.of();
            List<EllipseZoneSpec> e = ellipses != null ? ellipses : List.of();
            double[][][] qa = new double[q.size()][][];
            for (int k = 0; k < q.size(); k++) {
                ScreenRegionSpec z = q.get(k);
                qa[k] = new double[][]{
                        {z.getTopLeft().getX(), z.getTopRight().getX(), z.getBottomRight().getX(), z.getBottomLeft().getX()},
                        {z.getTopLeft().getY(), z.getTopRight().getY(), z.getBottomRight().getY(), z.getBottomLeft().getY()}};
            }
            double[][] ea = new double[e.size()][];
            for (int k = 0; k < e.size(); k++) {
                EllipseZoneSpec z = e.get(k);
                ea[k] = new double[]{z.getCx(), z.getCy(), z.getRx(), z.getRy()};
            }
            return new Zones(qa, ea);
        }

        public boolean isEmpty() { return quads.length == 0 && ellipses.length == 0; }
    }

    /** Distance (px) from a point to an axis-aligned ellipse's boundary; 0 inside. Exact for a
     * circle; for an oval it is the first-order estimate (t-1)/|grad t| of the normalized radius
     * t = hypot((x-cx)/rx, (y-cy)/ry), accurate right at the edge - which is all the soft edge
     * (a few px) ever looks at. */
    public static double ellipseDistance(double cx, double cy, double rx, double ry, double px, double py) {
        double u = (px - cx) / rx, v = (py - cy) / ry;
        double t = Math.hypot(u, v);
        if (t <= 1) return 0;
        return (t - 1) * t / Math.hypot(u / rx, v / ry);
    }

    /** How much light is let through at a point by the no-light zones: 0 inside any zone, rising
     * linearly to 1 over OCCLUDER_FEATHER_PX outside it (so the cut follows the object without a
     * razor-hard line), 1 away from every zone. */
    public static double occlusion(Zones zones, double px, double py) {
        double pass = 1;
        for (double[][] z : zones.quads) {
            double d = polygonDistance(z[0], z[1], px, py);
            if (d <= 0) return 0;
            pass = Math.min(pass, Math.min(1, d / OCCLUDER_FEATHER_PX));
        }
        for (double[] e : zones.ellipses) {
            double d = ellipseDistance(e[0], e[1], e[2], e[3], px, py);
            if (d <= 0) return 0;
            pass = Math.min(pass, Math.min(1, d / OCCLUDER_FEATHER_PX));
        }
        return pass;
    }

    public static double glowExponent(double softness) {
        return GLOW_K_MAX - (GLOW_K_MAX - GLOW_K_MIN) * (Math.max(0, Math.min(100, softness)) / 100.0);
    }

    /** Glow opacity 0..1 at a point given as a distance from the hole (see distanceOutside). */
    public static double glowAlpha(double d, double reach, double brightness, double softness) {
        if (d <= 0 || reach <= 0 || d >= reach) return 0;
        double edge = GLOW_EDGE_BASE + GLOW_EDGE_PER_BRIGHTNESS * brightness;
        return edge * Math.pow(1 - d / reach, glowExponent(softness)) * Math.min(1, d / EDGE_RAMP_PX);
    }

    /** Shadow opacity 0..1 at a point given as a distance from the hole. */
    public static double shadowAlpha(double d, double reach, double shadowIntensity) {
        double r = reach * SHADOW_REACH_FACTOR;
        if (d <= 0 || r <= 0 || d >= r) return 0;
        return SHADOW_MAX_ALPHA * shadowIntensity * Math.pow(1 - d / r, SHADOW_EXPONENT) * Math.min(1, d / EDGE_RAMP_PX);
    }

    /** One byte (0..255) per pixel of the shape's lit box, row-major; sampled at pixel centres. */
    public static byte[] glowMask(Shape s, double brightness, double softness, Zones zones) {
        byte[] out = new byte[s.w * s.h];
        for (int j = 0; j < s.h; j++) {
            for (int i = 0; i < s.w; i++) {
                double px = s.x + i + 0.5, py = s.y + j + 0.5;
                double a = glowAlpha(distanceOutside(s, px, py), s.reach, brightness, softness);
                if (a > 0 && !zones.isEmpty()) a *= occlusion(zones, px, py);
                out[j * s.w + i] = (byte) Math.round(255 * a);
            }
        }
        return out;
    }

    public static byte[] shadowMask(Shape s, double shadowIntensity, Zones zones) {
        byte[] out = new byte[s.w * s.h];
        for (int j = 0; j < s.h; j++) {
            for (int i = 0; i < s.w; i++) {
                double px = s.x + i + 0.5, py = s.y + j + 0.5;
                double a = shadowAlpha(distanceOutside(s, px, py), s.reach, shadowIntensity);
                if (a > 0 && !zones.isEmpty()) a *= occlusion(zones, px, py);
                out[j * s.w + i] = (byte) Math.round(255 * a);
            }
        }
        return out;
    }

    /** Writes an 8-bit grayscale PNG (raw sample values, no gamma chunk) that ffmpeg reads as gray. */
    public static void writeGrayPng(byte[] data, int w, int h, Path target) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        img.getRaster().setDataElements(0, 0, w, h, data);
        if (!ImageIO.write(img, "png", target.toFile())) {
            throw new IOException("No PNG writer available for " + target);
        }
    }
}
