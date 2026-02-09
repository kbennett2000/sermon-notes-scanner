package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DocQuadLetterboxTest {

    @Test
    public void roundtrip_forward_inverse_isIdentity() {
        DocQuadLetterbox lb = DocQuadLetterbox.create(1000, 500, 256, 256);

        double[][] pts = new double[][]{
                {0.0, 0.0},
                {999.0, 0.0},
                {999.0, 499.0},
                {0.0, 499.0},
                {123.4, 321.0},
        };

        for (double[] p : pts) {
            double[] q = lb.forward(p[0], p[1]);
            double[] r = lb.inverse(q[0], q[1]);
            assertTrue(Math.abs(r[0] - p[0]) < 1e-9);
            assertTrue(Math.abs(r[1] - p[1]) < 1e-9);
        }
    }
}
