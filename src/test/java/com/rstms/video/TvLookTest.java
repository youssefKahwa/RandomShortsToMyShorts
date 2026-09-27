package com.rstms.video;

import com.rstms.model.ScreenRegionSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TvLookTest {

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

    /** A TV filmed at an angle: no two sides parallel. */
    private static final ScreenRegionSpec SKEWED = quad(100, 50, 420, 80, 400, 300, 90, 280);

    private static void assertColor(double[] expected, double[] actual) {
        assertArrayEquals(expected, actual, 1e-9);
    }

    // ---- colour ----

    @Test
    void neutralSettingsLeaveColoursUntouched() {
        double[][] m = TvLook.colorMatrix(1, 1, 1, 0, 0);
        assertTrue(TvLook.isIdentity(m));
        assertColor(new double[]{0.2, 0.5, 0.9}, TvLook.applyColor(m, 0.2, 0.5, 0.9));
    }

    @Test
    void blackLevelLiftsBlackAndKeepsWhite() {
        double[][] m = TvLook.colorMatrix(1, 1, 1, 0, 0.1);
        assertColor(new double[]{0.1, 0.1, 0.1}, TvLook.applyColor(m, 0, 0, 0));
        assertColor(new double[]{1, 1, 1}, TvLook.applyColor(m, 1, 1, 1));
        assertColor(new double[]{0.55, 0.55, 0.55}, TvLook.applyColor(m, 0.5, 0.5, 0.5));
    }

    @Test
    void contrastPivotsOnMidGrey() {
        double[][] m = TvLook.colorMatrix(1, 0.7, 1, 0, 0);
        assertColor(new double[]{0.5, 0.5, 0.5}, TvLook.applyColor(m, 0.5, 0.5, 0.5));
        assertColor(new double[]{0.85, 0.85, 0.85}, TvLook.applyColor(m, 1, 1, 1));
        assertColor(new double[]{0.15, 0.15, 0.15}, TvLook.applyColor(m, 0, 0, 0));
    }

    @Test
    void saturationKeepsGreysAndZeroGivesLuma() {
        double[][] grey = TvLook.colorMatrix(1, 1, 0, 0, 0);
        assertColor(new double[]{TvLook.LUMA_R, TvLook.LUMA_R, TvLook.LUMA_R}, TvLook.applyColor(grey, 1, 0, 0));
        double[][] vivid = TvLook.colorMatrix(1, 1, 1.5, 0, 0);
        assertColor(new double[]{0.3, 0.3, 0.3}, TvLook.applyColor(vivid, 0.3, 0.3, 0.3));
    }

    @Test
    void temperatureMovesRedAndBlueOppositely() {
        assertColor(new double[]{0.55, 0.5, 0.45}, TvLook.applyColor(TvLook.colorMatrix(1, 1, 1, 1, 0), 0.5, 0.5, 0.5));
        assertColor(new double[]{0.45, 0.5, 0.55}, TvLook.applyColor(TvLook.colorMatrix(1, 1, 1, -1, 0), 0.5, 0.5, 0.5));
    }

    /** The single matrix must equal the documented steps applied one after the other. */
    @Test
    void matrixEqualsTheStepsInOrder() {
        double bright = 0.92, con = 0.9, sat = 0.8, temp = -0.3, black = 0.06;
        double[][] m = TvLook.colorMatrix(bright, con, sat, temp, black);
        Random rnd = new Random(7);
        for (int n = 0; n < 200; n++) {
            double[] in = {rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble()};
            double[] v = in.clone();
            for (int c = 0; c < 3; c++) v[c] = (v[c] * bright - 0.5) * con + 0.5;
            double l = TvLook.LUMA_R * v[0] + TvLook.LUMA_G * v[1] + TvLook.LUMA_B * v[2];
            for (int c = 0; c < 3; c++) v[c] = l + sat * (v[c] - l);
            v[0] *= 1 + TvLook.TEMPERATURE_GAIN * temp;
            v[2] *= 1 - TvLook.TEMPERATURE_GAIN * temp;
            for (int c = 0; c < 3; c++) v[c] = Math.max(0, Math.min(1, black + v[c] * (1 - black)));
            assertColor(v, TvLook.applyColor(m, in[0], in[1], in[2]));
        }
    }

    @Test
    void clampsOnlyAtTheEnd() {
        double[][] m = TvLook.colorMatrix(1.3, 1, 1, 0, 0);
        assertColor(new double[]{1, 1, 1}, TvLook.applyColor(m, 1, 1, 1));
        assertColor(new double[]{0.65, 0.65, 0.65}, TvLook.applyColor(m, 0.5, 0.5, 0.5));
    }

    @Test
    void cubeLutHasTheFormatLutThreeDReads(@TempDir Path dir) throws Exception {
        double[][] m = TvLook.colorMatrix(0.92, 0.92, 0.92, -0.2, 0.05);
        Path cube = dir.resolve("t.cube");
        TvLook.writeCubeLut(m, cube);
        List<String> lines = Files.readAllLines(cube);
        int n = TvLook.LUT_SIZE;
        assertEquals("LUT_3D_SIZE " + n, lines.get(1));
        List<String> data = lines.subList(4, lines.size());
        assertEquals(n * n * n, data.size());
        assertRow(TvLook.applyColor(m, 0, 0, 0), data.get(0));
        // Red varies fastest, then green, then blue.
        assertRow(TvLook.applyColor(m, 1.0 / (n - 1), 0, 0), data.get(1));
        assertRow(TvLook.applyColor(m, 0, 1.0 / (n - 1), 0), data.get(n));
        assertRow(TvLook.applyColor(m, 0, 0, 1.0 / (n - 1)), data.get(n * n));
        assertRow(TvLook.applyColor(m, 1, 1, 1), data.get(data.size() - 1));
    }

    private static void assertRow(double[] expected, String row) {
        String[] p = row.trim().split(" ");
        for (int c = 0; c < 3; c++) assertEquals(expected[c] + TvLook.LUT_ROUNDING_BIAS, Double.parseDouble(p[c]), 1e-6);
    }

    // ---- geometry ----

    @Test
    void homographyMapsTheCornersAndRoundTrips() {
        TvLook.Geometry g = TvLook.geometry(SKEWED);
        assertArrayEquals(new double[]{100, 50}, TvLook.toFrame(g, 0, 0), 1e-9);
        assertArrayEquals(new double[]{420, 80}, TvLook.toFrame(g, 1, 0), 1e-9);
        assertArrayEquals(new double[]{400, 300}, TvLook.toFrame(g, 1, 1), 1e-9);
        assertArrayEquals(new double[]{90, 280}, TvLook.toFrame(g, 0, 1), 1e-9);
        Random rnd = new Random(3);
        for (int k = 0; k < 200; k++) {
            double u = rnd.nextDouble(), v = rnd.nextDouble();
            double[] p = TvLook.toFrame(g, u, v);
            double[] back = TvLook.toScreen(g, p[0], p[1]);
            assertNotNull(back);
            assertArrayEquals(new double[]{u, v}, back, 1e-9);
        }
    }

    @Test
    void pointsOffTheScreenHaveNoScreenPosition() {
        TvLook.Geometry g = TvLook.geometry(SKEWED);
        assertNull(TvLook.toScreen(g, 95, 40));   // above-left of the top-left corner
        assertNull(TvLook.toScreen(g, 419, 295)); // inside the bounding box, outside the slanted right side
        assertNull(TvLook.toScreen(g, 2000, 2000));
        assertNotNull(TvLook.toScreen(g, 250, 170));
    }

    @Test
    void rectangleFrameIsPlainlyLinear() {
        TvLook.Geometry g = TvLook.geometry(rect(77, 373, 803, 759));
        double[] uv = TvLook.toScreen(g, 77 + 726 * 0.25, 373 + 386 * 0.8);
        assertArrayEquals(new double[]{0.25, 0.8}, uv, 1e-9);
        // The box starts on an even pixel (ffmpeg's overlay rounds odd positions down on yuv420p), so
        // it grows by one pixel on those sides rather than shifting, and still covers the whole frame.
        assertEquals(76, g.x);
        assertEquals(372, g.y);
        assertEquals(0, g.w % 2);
        assertEquals(0, g.h % 2);
        assertTrue(g.x + g.w >= 803 && g.y + g.h >= 759);
        // Odd sizes grow by one pixel so the yuv420p layers are valid.
        TvLook.Geometry odd = TvLook.geometry(rect(10, 20, 111, 121.5));
        assertEquals(10, odd.x);
        assertEquals(102, odd.w);
        assertEquals(102, odd.h);
    }

    @Test
    void collapsedFrameIsDegenerateAndDrawsNothing() {
        TvLook.Geometry g = TvLook.geometry(rect(50, 50, 50, 50));
        assertTrue(g.isDegenerate());
        byte[] m = TvLook.darkMask(g, 1, 1);
        for (byte b : m) assertEquals(0, b);
    }

    // ---- glass ----

    @Test
    void vignetteIsZeroInTheCentreAndStrongestInTheCorners() {
        assertEquals(0, TvLook.darkAlpha(0.5, 0.5, 100, 0.3, 0), 1e-12);
        double corner = TvLook.darkAlpha(0, 0, 100, 0.3, 0);
        double edge = TvLook.darkAlpha(0.5, 0, 100, 0.3, 0);
        assertEquals(0.3 * TvLook.VIGNETTE_MAX, corner, 1e-12);
        assertTrue(corner > edge && edge > 0);
    }

    @Test
    void panelLinesHaveTheirPitch() {
        TvLook.Geometry g = TvLook.geometry(rect(0, 0, 400, 300));
        assertEquals(100, g.lineCount, 1e-12);
        assertEquals(0, TvLook.darkAlpha(0.5, 10 / 100.0, g.lineCount, 0, 1), 1e-12);
        assertEquals(TvLook.LINE_MAX, TvLook.darkAlpha(0.5, 10.5 / 100.0, g.lineCount, 0, 1), 1e-12);
        // In the mask: one period every LINE_PITCH_PX rows.
        byte[] m = TvLook.darkMask(g, 0, 1);
        int col = 200;
        for (int y = 30; y < 60; y++) {
            assertEquals(m[y * g.w + col] & 0xFF, m[(y + 3) * g.w + col] & 0xFF, 1);
        }
    }

    @Test
    void reflectionPeaksAtItsPosition() {
        double t = Math.toRadians(TvLook.GLARE_ANGLE_DEG), v = 0.2, pos = 0.4;
        double uPeak = (pos * (Math.cos(t) + Math.sin(t)) - v * Math.sin(t)) / Math.cos(t);
        double peak = TvLook.glareAlpha(uPeak, v, 1, pos);
        assertTrue(peak > TvLook.glareAlpha(uPeak - 0.05, v, 1, pos));
        assertTrue(peak > TvLook.glareAlpha(uPeak + 0.05, v, 1, pos));
        assertEquals(0, TvLook.glareAlpha(uPeak, v, 0, pos), 1e-12);
    }

    @Test
    void masksAreZeroOffTheScreen() {
        TvLook.Geometry g = TvLook.geometry(SKEWED);
        byte[] dark = TvLook.darkMask(g, 1, 0.5), glare = TvLook.glareMask(g, 1, 0.3);
        int on = 0;
        for (int j = 0; j < g.h; j++) {
            for (int i = 0; i < g.w; i++) {
                double px = g.x + i + 0.5, py = g.y + j + 0.5;
                boolean onScreen = TvLook.toScreen(g, px, py) != null;
                if (!onScreen) {
                    assertEquals(0, dark[j * g.w + i]);
                    assertEquals(0, glare[j * g.w + i]);
                } else {
                    on++;
                }
            }
        }
        // The skewed frame covers most of its box - a sanity check that the masks are not empty.
        assertTrue(on > g.w * g.h / 2);
        assertTrue((glare[(g.h / 4) * g.w + g.w / 3] & 0xFF) > 0);
    }
}
