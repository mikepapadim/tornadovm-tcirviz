import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * A histogram with KernelContext atomics: every thread reads one value and atomically increments its
 * bin. The bounds check stays because the global size may be rounded up past the input's length. Watch
 * context.atomicAdd become atomicAdd (CUDA), atomic_add (OpenCL) or a helper around
 * atomic_fetch_add_explicit (Metal).
 */
public class Histogram {

    public static void histogram(KernelContext context, IntArray input, IntArray bins) {
        int i = context.globalIdx;
        if (i < input.getSize()) {
            context.atomicAdd(bins, input.get(i), 1);
        }
    }
}
