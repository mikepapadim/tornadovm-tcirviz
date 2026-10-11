import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * A work-group reduction written with KernelContext: thread ids, local (shared) memory and barriers
 * map one to one to CUDA and OpenCL. Each group writes one partial sum.
 */
public class Reduction {

    public static void sum(KernelContext context, FloatArray input, FloatArray partials) {
        int gid = context.globalIdx;
        int lid = context.localIdx;
        int groupSize = context.localGroupSizeX;
        float[] local = context.allocateFloatLocalArray(256);
        local[lid] = input.get(gid);
        for (int stride = groupSize / 2; stride > 0; stride /= 2) {
            context.localBarrier();
            if (lid < stride) {
                local[lid] += local[lid + stride];
            }
        }
        if (lid == 0) {
            partials.set(context.groupIdx, local[0]);
        }
    }
}
