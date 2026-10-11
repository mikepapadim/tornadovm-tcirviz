import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.matrix.Matrix2DFloat;

/**
 * Two @Parallel loops become a 2D grid of threads; the inner k loop stays a loop in every thread.
 * Matrix2DFloat.get/set are Java methods: follow the `inline` stage to see them disappear.
 */
public class MatrixMultiply {

    public static void mxm(Matrix2DFloat a, Matrix2DFloat b, Matrix2DFloat c, int n) {
        for (@Parallel int i = 0; i < n; i++) {
            for (@Parallel int j = 0; j < n; j++) {
                float sum = 0.0f;
                for (int k = 0; k < n; k++) {
                    sum += a.get(i, k) * b.get(k, j);
                }
                c.set(i, j, sum);
            }
        }
    }
}
