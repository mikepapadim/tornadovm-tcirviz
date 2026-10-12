import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * C = A * B with KernelContext and local memory: a TS x TS work-group stages one tile of A and one of
 * B in local (shared) memory, waits at a barrier, and reuses them TS times. Follow `aTile` to see the
 * local array become `__shared__` (CUDA), `__local` (OpenCL) or `threadgroup` (Metal) memory.
 */
public class LocalMemoryMatmul {

    private static final int TS = 16;

    public static void matmul(KernelContext context, FloatArray a, FloatArray b, FloatArray c, int size) {
        int row = context.localIdx;
        int column = context.localIdy;
        int globalRow = TS * context.groupIdx + row;
        int globalColumn = TS * context.groupIdy + column;

        float[] aTile = context.allocateFloatLocalArray(TS * TS);
        float[] bTile = context.allocateFloatLocalArray(TS * TS);

        float sum = 0.0f;
        for (int t = 0; t < size / TS; t++) {
            aTile[column * TS + row] = a.get((TS * t + column) * size + globalRow);
            bTile[column * TS + row] = b.get(globalColumn * size + TS * t + row);
            context.localBarrier();
            for (int k = 0; k < TS; k++) {
                sum += aTile[k * TS + row] * bTile[column * TS + k];
            }
            context.localBarrier();
        }
        c.set(globalColumn * size + globalRow, sum);
    }
}
