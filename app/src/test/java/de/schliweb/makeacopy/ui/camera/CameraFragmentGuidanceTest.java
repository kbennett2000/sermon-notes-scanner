package de.schliweb.makeacopy.ui.camera;

import de.schliweb.makeacopy.framing.FramingResult;
import de.schliweb.makeacopy.framing.GuidanceHint;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class CameraFragmentGuidanceTest {

    private static FramingResult fr(GuidanceHint hint, boolean hasDoc) {
        return new FramingResult(
                0f, 0f, 0f, 1f, 0f, 0f,
                hint,
                hasDoc
        );
    }

    private static Object callComputeEffectiveFraming(CameraFragment fragment, FramingResult base, double rawScore) throws Exception {
        Method m = CameraFragment.class.getDeclaredMethod("computeEffectiveFraming", FramingResult.class, double.class);
        m.setAccessible(true);
        return m.invoke(fragment, base, rawScore);
    }

    private static GuidanceHint readEffHint(Object eff) throws Exception {
        Field fRes = eff.getClass().getDeclaredField("result");
        fRes.setAccessible(true);
        Object fr = fRes.get(eff);
        Field fHint = fr.getClass().getDeclaredField("hint");
        fHint.setAccessible(true);
        return (GuidanceHint) fHint.get(fr);
    }

    private static boolean readEffHasDoc(Object eff) throws Exception {
        Field fRes = eff.getClass().getDeclaredField("result");
        fRes.setAccessible(true);
        Object fr = fRes.get(eff);
        Field fHas = fr.getClass().getDeclaredField("hasDocument");
        fHas.setAccessible(true);
        return (boolean) fHas.get(fr);
    }

    private static boolean readEffSuppressDistance(Object eff) throws Exception {
        Field f = eff.getClass().getDeclaredField("suppressDistance");
        f.setAccessible(true);
        return (boolean) f.get(eff);
    }

    private static GuidanceHint callComputeEffectiveOrientation(CameraFragment fragment, int bucketDeg, double conf, Object eff) throws Exception {
        Method m = CameraFragment.class.getDeclaredMethod("computeEffectiveOrientation", int.class, double.class, eff.getClass());
        m.setAccessible(true);
        Object res = m.invoke(fragment, bucketDeg, conf, eff);
        return (GuidanceHint) res; // may be null
    }

    @Test
    public void testComputeEffectiveFraming_setsNoDocBelowThreshold() throws Exception {
        CameraFragment fragment = new CameraFragment();

        // Base indicates a movement hint with hasDocument=true, but score is low
        FramingResult base = fr(GuidanceHint.MOVE_BACK, true);
        Object eff = callComputeEffectiveFraming(fragment, base, 0.05 /* rawScore < threshold */);

        Assert.assertEquals(GuidanceHint.NO_DOCUMENT_DETECTED, readEffHint(eff));
        Assert.assertFalse(readEffHasDoc(eff));
        Assert.assertTrue(readEffSuppressDistance(eff));
    }

    @Test
    public void testComputeEffectiveFraming_keepsBaseWhenScoreGood() throws Exception {
        CameraFragment fragment = new CameraFragment();

        FramingResult base = fr(GuidanceHint.MOVE_LEFT, true);
        Object eff = callComputeEffectiveFraming(fragment, base, 0.8 /* good score */);

        Assert.assertEquals(GuidanceHint.MOVE_LEFT, readEffHint(eff));
        Assert.assertTrue(readEffHasDoc(eff));
        Assert.assertFalse(readEffSuppressDistance(eff));
    }

    @Test
    public void testComputeEffectiveOrientation_contextAndConfidence() throws Exception {
        CameraFragment fragment = new CameraFragment();

        // First create an effective framing that represents no document
        Object effNoDoc = callComputeEffectiveFraming(fragment, fr(GuidanceHint.OK, false), 0.0);

        // Below confidence threshold → null
        GuidanceHint h0 = callComputeEffectiveOrientation(fragment, 0, 0.10, effNoDoc);
        Assert.assertNull(h0);

        // Adequate confidence with bucket 0 → PORTRAIT
        GuidanceHint h1 = callComputeEffectiveOrientation(fragment, 0, 0.50, effNoDoc);
        Assert.assertEquals(GuidanceHint.ORIENTATION_PORTRAIT_TIP, h1);

        // Adequate confidence with bucket 90 → LANDSCAPE
        GuidanceHint h2 = callComputeEffectiveOrientation(fragment, 90, 0.80, effNoDoc);
        Assert.assertEquals(GuidanceHint.ORIENTATION_LANDSCAPE_TIP, h2);

        // Now create an effective framing that represents a present document
        Object effHasDoc = callComputeEffectiveFraming(fragment, fr(GuidanceHint.MOVE_LEFT, true), 0.9);
        GuidanceHint hDoc = callComputeEffectiveOrientation(fragment, 90, 0.80, effHasDoc);
        // Orientation tip should be suppressed when a plausible document is present
        Assert.assertNull(hDoc);
    }
}
