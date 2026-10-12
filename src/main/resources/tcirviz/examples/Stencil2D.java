import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * A 5-point Jacobi stencil with KernelContext's 2-D ids: no loops in the kernel, one thread per grid
 * point, and the edges left untouched. Compare with BlurFilter's @Parallel loop nest: here the ids
 * come straight from the context.
 */
public class Stencil2D {

    public static void jacobi(KernelContext context, FloatArray in, FloatArray out, int width, int height) {
        int x = context.globalIdx;
        int y = context.globalIdy;
        if (x > 0 && y > 0 && x < width - 1 && y < height - 1) {
            int i = y * width + x;
            out.set(i, 0.25f * (in.get(i - 1) + in.get(i + 1) + in.get(i - width) + in.get(i + width)));
        }
    }
}
