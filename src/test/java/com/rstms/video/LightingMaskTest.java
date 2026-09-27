package com.rstms.video;

import com.rstms.model.EllipseZoneSpec;
import com.rstms.model.ScreenRegionSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingMaskTest {

    private static ScreenRegionSpec quad(double tlx, double tly, double trx, double try_, double brx, double bry, double blx, double bly) {
        ScreenRegionSpec r = new ScreenRegionSpec();
        r.setTopLeft(new ScreenRegionSpec.Corner(tlx, tly));
        r.setTopRight(new ScreenRegionSpec.Corner(trx, try_));
        r.setBottomRight(new ScreenRegionSpec.Corner(brx, bry));
        r.setBottomLeft(new ScreenRegionSpec.Corner(blx, bly));
        return r;
    }

    private static ScreenRegionSpec rect(double x0, double y0, double x1, double y1) {
        return quad(x0, y0, x1, y0, x1, y1, x0, y1);
    }

    /** The real TV in the user's footage: outer edge of its black border. */
    private static final ScreenRegionSpec TV = rect(76, 363, 813, 782);

    @Test
    void holeIsExactlyTheFrame() {
        LightingMask.Shape s = LightingMask.shape(TV, 1.3);
        double[] ex = {76, 813, 813, 76}, ey = {363, 363, 782, 782};
        for (int i = 0; i < 4; i++) {
            assertEquals(ex[i], s.hx[i], 1e-12);
            assertEquals(ey[i], s.hy[i], 1e-12);
        }
    }

    @Test
    void reversedWindingGivesTheSameLight() {
        LightingMask.Shape a = LightingMask.shape(TV, 1.4);
        LightingMask.Shape b = LightingMask.shape(quad(813, 363, 76, 363, 76, 782, 813, 782), 1.4);
        assertEquals(a.x, b.x);
        assertEquals(a.w, b.w);
        assertEquals(a.reach, b.reach, 1e-12);
        for (double[] p : new double[][]{{60, 572}, {445, 340}, {830, 700}, {445, 572}, {78, 572}}) {
            assertEquals(LightingMask.distanceOutside(a, p[0], p[1]), LightingMask.distanceOutside(b, p[0], p[1]), 1e-9);
        }
    }

    @Test
    void noLightInsideTheFrame() {
        LightingMask.Shape s = LightingMask.shape(TV, 1.3);
        assertEquals(0, LightingMask.distanceOutside(s, 445, 572), 1e-9);  // screen centre
        assertEquals(0, LightingMask.distanceOutside(s, 78, 572), 1e-9);   // on the black border, inside the frame
        assertEquals(0, LightingMask.glowAlpha(0, s.reach, 1.0, 50), 1e-9);
        assertEquals(0, LightingMask.shadowAlpha(0, s.reach, 1.0), 1e-9);
    }

    @Test
    void lightStartsRightOutsideTheFrameAndFadesToZeroAtReach() {
        LightingMask.Shape s = LightingMask.shape(TV, 1.3);
        assertEquals(16, LightingMask.distanceOutside(s, 60, 572), 1e-9);  // 16px left of x=76
        double a3 = LightingMask.glowAlpha(3, s.reach, 0.6, 50);
        double a20 = LightingMask.glowAlpha(20, s.reach, 0.6, 50);
        assertTrue(a3 > a20, "brightest next to the frame");
        assertTrue(a20 > 0);
        assertEquals(0, LightingMask.glowAlpha(s.reach, s.reach, 0.6, 50), 1e-12);
        assertTrue(LightingMask.glowAlpha(1, s.reach, 0.6, 50) < a3, "soft contact ramp over the first 3px");
    }

    @Test
    void reachIsSpreadOfTheMeanFrameSize() {
        LightingMask.Shape s = LightingMask.shape(TV, 1.4);
        double mean = ((813 - 76) + (782 - 363)) / 2.0;
        assertEquals(0.2 * mean, s.reach, 1e-9);
    }

    @Test
    void brightnessAndSoftnessBehaveMonotonically() {
        double reach = 100;
        assertTrue(LightingMask.glowAlpha(10, reach, 0.9, 50) > LightingMask.glowAlpha(10, reach, 0.2, 50));
        // A softer (higher) softness = lower exponent = light lingers further out.
        assertTrue(LightingMask.glowAlpha(60, reach, 0.6, 100) > LightingMask.glowAlpha(60, reach, 0.6, 0));
    }

    @Test
    void litBoxContainsTheFrameAndReachWithEvenSize() {
        LightingMask.Shape s = LightingMask.shape(TV, 1.3);
        assertTrue(s.w % 2 == 0 && s.h % 2 == 0);
        assertTrue(s.x <= 76 - s.reach + 1 && s.x + s.w >= 813 + s.reach - 1);
        assertTrue(s.y <= 363 - s.reach + 1 && s.y + s.h >= 782 + s.reach - 1);
    }

    /** ffmpeg's overlay rounds an odd position down to even on yuv420p: the box must start on an even
     * pixel (including negative ones, past the frame's top/left) or the light is drawn 1 px off. */
    @Test
    void litBoxStartsOnAnEvenPixel() {
        for (double spread : new double[]{1.1, 1.3, 1.37, 1.6, 2.0}) {
            for (ScreenRegionSpec f : new ScreenRegionSpec[]{TV, rect(3, 5, 400, 300), rect(-7.5, 1, 90, 51)}) {
                LightingMask.Shape s = LightingMask.shape(f, spread);
                assertEquals(0, Math.floorMod(s.x, 2));
                assertEquals(0, Math.floorMod(s.y, 2));
            }
        }
    }

    @Test
    void maskIsZeroInsideTheFrameAndPositiveJustOutside() {
        LightingMask.Shape s = LightingMask.shape(TV, 1.3);
        byte[] m = LightingMask.glowMask(s, 0.6, 50, LightingMask.Zones.none());
        assertEquals(0, m[(572 - s.y) * s.w + (445 - s.x)] & 0xFF);
        assertEquals(0, m[(572 - s.y) * s.w + (78 - s.x)] & 0xFF);   // black border
        assertTrue((m[(572 - s.y) * s.w + (69 - s.x)] & 0xFF) > 0);   // 7px outside the frame
    }

    /** A cabinet standing in front of the TV, below it. */
    private static final ScreenRegionSpec CABINET = rect(295, 790, 545, 900);

    private static LightingMask.Zones boxes(ScreenRegionSpec... q) {
        return LightingMask.Zones.of(java.util.List.of(q), java.util.List.of());
    }
    private static LightingMask.Zones rounds(EllipseZoneSpec... e) {
        return LightingMask.Zones.of(java.util.List.of(), java.util.List.of(e));
    }

    @Test
    void circleZoneCutsExactlyTheDiscWithASoftEdge() {
        LightingMask.Zones z = rounds(new EllipseZoneSpec(420, 840, 50, 50));
        assertEquals(0, LightingMask.occlusion(z, 420, 840), 1e-12);             // centre
        assertEquals(0, LightingMask.occlusion(z, 469, 840), 1e-12);             // 1px inside the rim
        assertEquals(0.5, LightingMask.occlusion(z, 472, 840), 1e-9);            // 2px outside: half
        assertEquals(0.5, LightingMask.occlusion(z, 420 + 52 / Math.sqrt(2), 840 + 52 / Math.sqrt(2)), 1e-9); // same on the diagonal
        assertEquals(1, LightingMask.occlusion(z, 480, 840), 1e-12);             // 10px outside: untouched
        assertEquals(1, LightingMask.occlusion(z, 460, 800), 1e-12);             // bounding-box corner, outside the disc
    }

    @Test
    void ovalZoneUsesItsOwnTwoRadiiAndMatchesTheExactDistanceAtTheEdge() {
        LightingMask.Zones z = rounds(new EllipseZoneSpec(400, 800, 80, 30));
        assertEquals(0, LightingMask.occlusion(z, 478, 800), 1e-12);             // inside along the long axis
        assertEquals(0, LightingMask.occlusion(z, 400, 829), 1e-12);             // inside along the short axis
        assertEquals(0.5, LightingMask.occlusion(z, 482, 800), 1e-9);            // 2px past the long end
        assertEquals(0.5, LightingMask.occlusion(z, 400, 832), 1e-9);            // 2px past the short end
        assertEquals(1, LightingMask.occlusion(z, 400, 790 - 30), 1e-12);        // far outside above
        // Off-axis, 2px outside the rim along the normal: the estimate is within 0.1px of exact.
        double t = Math.PI / 5, ex = 400 + 80 * Math.cos(t), ey = 800 + 30 * Math.sin(t);
        double nx = Math.cos(t) / 80, ny = Math.sin(t) / 30, nl = Math.hypot(nx, ny);
        double d = LightingMask.ellipseDistance(400, 800, 80, 30, ex + 2 * nx / nl, ey + 2 * ny / nl);
        assertEquals(2, d, 0.1);
    }

    @Test
    void roundAndBoxZonesCombine() {
        LightingMask.Zones z = LightingMask.Zones.of(java.util.List.of(CABINET), java.util.List.of(new EllipseZoneSpec(40, 500, 20, 20)));
        assertEquals(0, LightingMask.occlusion(z, 420, 840), 1e-12);
        assertEquals(0, LightingMask.occlusion(z, 40, 500), 1e-12);
        assertEquals(1, LightingMask.occlusion(z, 40, 300), 1e-12);
        LightingMask.Shape s = LightingMask.shape(TV, 1.6);
        byte[] shadow = LightingMask.shadowMask(s, 0.4, z);
        byte[] shadowFree = LightingMask.shadowMask(s, 0.4, LightingMask.Zones.none());
        int inCircle = (500 - s.y) * s.w + (40 - s.x);
        assertTrue((shadowFree[inCircle] & 0xFF) > 0, "the circle is somewhere the shadow would otherwise reach");
        assertEquals(0, shadow[inCircle] & 0xFF);
    }

    @Test
    void noLightInsideANoLightZoneAndSoftEdgeAroundIt() {
        LightingMask.Shape s = LightingMask.shape(TV, 1.6);
        LightingMask.Zones z = boxes(CABINET);
        assertEquals(0, LightingMask.occlusion(z, 420, 840), 1e-12);            // inside
        assertEquals(0, LightingMask.occlusion(z, 296, 791), 1e-12);            // just inside the corner
        assertEquals(0.5, LightingMask.occlusion(z, 293, 840), 1e-9);           // 2px left of it: half
        assertEquals(1, LightingMask.occlusion(z, 280, 840), 1e-12);            // 15px away: untouched
        assertEquals(1, LightingMask.occlusion(LightingMask.Zones.none(), 420, 840), 1e-12);

        byte[] glow = LightingMask.glowMask(s, 0.5, 0, boxes(CABINET));
        byte[] shadow = LightingMask.shadowMask(s, 0.4, boxes(CABINET));
        byte[] glowFree = LightingMask.glowMask(s, 0.5, 0, LightingMask.Zones.none());
        int in = (840 - s.y) * s.w + (420 - s.x), out = (840 - s.y) * s.w + (270 - s.x);
        assertTrue((glowFree[in] & 0xFF) > 0, "the zone is somewhere the light would otherwise reach");
        assertEquals(0, glow[in] & 0xFF);
        assertEquals(0, shadow[in] & 0xFF);
        assertEquals(glowFree[out] & 0xFF, glow[out] & 0xFF, "light next to the zone is unchanged");
    }

    @Test
    void severalZonesEachCutTheirOwnArea() {
        LightingMask.Zones z = boxes(CABINET, rect(10, 400, 60, 600));
        assertEquals(0, LightingMask.occlusion(z, 420, 840), 1e-12);
        assertEquals(0, LightingMask.occlusion(z, 30, 500), 1e-12);
        assertEquals(1, LightingMask.occlusion(z, 30, 300), 1e-12);
    }

    @Test
    void skewedFrameKeepsLightOffTheTvAndFollowsItsEdges() {
        LightingMask.Shape s = LightingMask.shape(quad(100, 100, 500, 140, 480, 420, 90, 380), 1.4);
        assertEquals(0, LightingMask.distanceOutside(s, 300, 260), 1e-9);
        // Beyond the slanted top edge but inside the axis-aligned bounding box: lit. The light follows
        // the real TV outline, not its bounding box.
        assertTrue(LightingMask.distanceOutside(s, 490, 112) > 0);
    }
}
