/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;
import org.opencv.core.Point;
import org.opencv.core.Size;

/**
 * Unit tests for the projective aspect-ratio estimator and the resulting warp target-size
 * computation introduced for issue #8 (Stage A, v3.7.2).
 *
 * <p>The tests synthetically project rectangles of known aspect ratio onto a virtual image plane
 * and verify that:
 *
 * <ul>
 *   <li>{@code estimateProjectiveAspectRatio} recovers the original W/H ratio with low error,
 *       substantially better than the legacy pixel-distance heuristic for tilted captures.
 *   <li>{@code computeWarpTargetSize(corners, w, h)} produces output dimensions whose ratio
 *       matches the projective estimate.
 *   <li>Degenerate inputs (e.g., near-fronto-parallel quad with collinear-like rows) fall back
 *       gracefully to the heuristic.
 * </ul>
 *
 * <p>The tests reach the package-private static methods reflectively to avoid coupling to
 * OpenCV native code (only {@link Point} and {@link Size}, which are pure Java POJOs in the
 * desktop OpenCV jar, are used).
 */
public class OpenCVUtilsAspectRatioTest {

  private static final double EPS_PROJECTIVE = 0.06; // 6% tolerance for projective recovery
  private static final double EPS_FALLBACK_BETTER = 0.1; // 10% — projective should beat heuristic

  /** Synthetic pinhole projection of a 3D point (X, Y, Z) onto image space. */
  private static Point project(
      double X, double Y, double Z, double f, double cx, double cy) {
    return new Point(f * X / Z + cx, f * Y / Z + cy);
  }

  /**
   * Generates the four image-space corners of a rectangle rotated by {@code rxDeg} around X then
   * {@code ryDeg} around Y, placed at distance {@code dist} along the optical axis. A non-zero
   * rotation around both axes is required so that no pair of edges remains parallel in the
   * image (which would render the projective recovery degenerate by construction).
   */
  private static Point[] tiltedRectangleCorners(
      double W,
      double H,
      double rxDeg,
      double ryDeg,
      double dist,
      double f,
      double imgW,
      double imgH) {
    double rx = Math.toRadians(rxDeg);
    double ry = Math.toRadians(ryDeg);
    double cx_ = Math.cos(rx), sx_ = Math.sin(rx);
    double cy_ = Math.cos(ry), sy_ = Math.sin(ry);
    double[][] obj = {
      {-W / 2, -H / 2, 0}, // TL
      {+W / 2, -H / 2, 0}, // TR
      {+W / 2, +H / 2, 0}, // BR
      {-W / 2, +H / 2, 0}, // BL
    };
    Point[] out = new Point[4];
    for (int i = 0; i < 4; i++) {
      double X0 = obj[i][0], Y0 = obj[i][1], Z0 = obj[i][2];
      // Rotate around X axis
      double X1 = X0;
      double Y1 = Y0 * cx_ - Z0 * sx_;
      double Z1 = Y0 * sx_ + Z0 * cx_;
      // Rotate around Y axis
      double X2 = X1 * cy_ + Z1 * sy_;
      double Y2 = Y1;
      double Z2 = -X1 * sy_ + Z1 * cy_;
      double Z = Z2 + dist;
      out[i] = project(X2, Y2, Z, f, imgW * 0.5, imgH * 0.5);
    }
    return out;
  }

  private static Method estimateMethod() throws Exception {
    Method m =
        OpenCVUtils.class.getDeclaredMethod(
            "estimateProjectiveAspectRatio", Point[].class, int.class, int.class);
    m.setAccessible(true);
    return m;
  }

  private static Method computeMethod() throws Exception {
    Method m =
        OpenCVUtils.class.getDeclaredMethod(
            "computeWarpTargetSize", Point[].class, int.class, int.class);
    m.setAccessible(true);
    return m;
  }

  private static Double estimate(Point[] corners, int w, int h) throws Exception {
    return (Double) estimateMethod().invoke(null, corners, w, h);
  }

  private static Size compute(Point[] corners, int w, int h) throws Exception {
    return (Size) computeMethod().invoke(null, corners, w, h);
  }

  /** Pixel-distance heuristic from the legacy implementation, for comparison purposes. */
  private static double heuristicRatio(Point[] c) {
    double wTop = Math.hypot(c[0].x - c[1].x, c[0].y - c[1].y);
    double wBottom = Math.hypot(c[2].x - c[3].x, c[2].y - c[3].y);
    double hLeft = Math.hypot(c[0].x - c[3].x, c[0].y - c[3].y);
    double hRight = Math.hypot(c[1].x - c[2].x, c[1].y - c[2].y);
    return Math.max(wTop, wBottom) / Math.max(hLeft, hRight);
  }

  @Test
  public void projectiveAspectRatio_a4Tilted_isAccurate() throws Exception {
    // A4 portrait: W:H = 210:297 → W/H ≈ 0.7071
    double imgW = 4000, imgH = 3000;
    double f = Math.max(imgW, imgH); // typical assumption
    Point[] c = tiltedRectangleCorners(210, 297, 25, 15, 1000, f, imgW, imgH);
    Double r = estimate(c, (int) imgW, (int) imgH);
    assertNotNull("projective estimate should be available", r);
    double truth = 210.0 / 297.0;
    double rel = Math.abs(r - truth) / truth;
    assertTrue("projective ratio off by " + rel + " (got " + r + ")", rel < EPS_PROJECTIVE);
  }

  @Test
  public void projectiveAspectRatio_longReceipt_beatsHeuristic() throws Exception {
    // Long receipt 1:3 — exactly the case CaqKa reported.
    double imgW = 4000, imgH = 3000;
    double f = Math.max(imgW, imgH);
    double W = 80, H = 240; // W/H = 1/3
    Point[] c = tiltedRectangleCorners(W, H, 35, 18, 600, f, imgW, imgH);
    Double r = estimate(c, (int) imgW, (int) imgH);
    assertNotNull(r);
    double truth = W / H; // 0.3333
    double relProjective = Math.abs(r - truth) / truth;
    double relHeuristic = Math.abs(heuristicRatio(c) - truth) / truth;
    assertTrue(
        "projective should beat heuristic: projective="
            + relProjective
            + " heuristic="
            + relHeuristic,
        relProjective + 1e-6 < relHeuristic);
    assertTrue(
        "projective ratio off by " + relProjective + " (got " + r + ")",
        relProjective < EPS_FALLBACK_BETTER);
  }

  @Test
  public void computeWarpTargetSize_matchesProjectiveRatio() throws Exception {
    double imgW = 4000, imgH = 3000;
    double f = Math.max(imgW, imgH);
    Point[] c = tiltedRectangleCorners(210, 297, 20, 12, 900, f, imgW, imgH);
    Size s = compute(c, (int) imgW, (int) imgH);
    assertTrue(s.width > 1 && s.height > 1);
    // Portrait quad → height should be the long side
    assertTrue("expected portrait orientation, got " + s.width + "x" + s.height, s.height >= s.width);
    double observed = s.width / s.height;
    double truth = 210.0 / 297.0;
    double rel = Math.abs(observed - truth) / truth;
    assertTrue("output ratio off by " + rel + " (size=" + s.width + "x" + s.height + ")", rel < EPS_PROJECTIVE);
  }

  @Test
  public void estimate_frontoParallelQuad_returnsNullForFallback() throws Exception {
    // A perfectly fronto-parallel rectangle: k2 == k3 == 1 → n*z == 0 → null
    int imgW = 4000, imgH = 3000;
    double cx = imgW * 0.5, cy = imgH * 0.5;
    Point[] c =
        new Point[] {
          new Point(cx - 500, cy - 700),
          new Point(cx + 500, cy - 700),
          new Point(cx + 500, cy + 700),
          new Point(cx - 500, cy + 700)
        };
    Double r = estimate(c, imgW, imgH);
    // Either null (degenerate) or numerically very close to truth — both are acceptable.
    if (r != null) {
      double truth = 1000.0 / 1400.0;
      assertEquals(truth, r, 0.05);
    }
    // Even in this case the wrapper must produce a sensible Size from the heuristic fallback.
    Size s = compute(c, imgW, imgH);
    assertTrue(s.width > 1 && s.height > 1);
  }

  @Test
  public void estimate_invalidInput_returnsNull() throws Exception {
    assertEquals(null, estimate(null, 100, 100));
    assertEquals(null, estimate(new Point[3], 100, 100));
    Point[] ok = {new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(0, 1)};
    assertEquals(null, estimate(ok, 0, 100));
    assertEquals(null, estimate(ok, 100, 0));
  }
}
