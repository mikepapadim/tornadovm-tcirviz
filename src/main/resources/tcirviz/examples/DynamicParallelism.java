import uk.ac.manchester.tornado.api.DeviceKernel;
import uk.ac.manchester.tornado.api.KernelContext;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Scales the rows of a ragged matrix (CSR: row r holds values[offsets[r] .. offsets[r + 1])). One
 * parent thread per row launches, from the GPU, a child grid sized to that row: CUDA Dynamic
 * Parallelism, so CUDA only. Rows of 3 and of 30,000 values each get the threads they need.
 *
 * The child is compiled into the same CUDA module as a __global__ kernel of its own: pick
 * DynamicParallelism.scaleRow_dp with the function selector. In the parent, the launch stays the
 * tornado.device_launch intrinsic through the IR stages; the CUDA source turns it into
 * scaleRow_dp<<<_dp_grid, _dp_block>>>(...) followed by a launch check. Arrays are passed as they are,
 * header included (the child has the same ABI). The grid is rounded up to whole blocks of 128, so the
 * child keeps its bounds check. On OpenCL and Metal, TC refuses the parent with TC-E4016.
 */
public class DynamicParallelism {

    private static final DeviceKernel SCALE_ROW = DeviceKernel.of(DynamicParallelism::scaleRow);

    public static void scaleRows(KernelContext context, IntArray offsets, FloatArray values, float factor, int rows) {
        int row = context.globalIdx;
        if (row < rows) {
            int start = offsets.get(row);
            int length = offsets.get(row + 1) - start;
            if (length > 0) {
                context.launch(SCALE_ROW, length, 128, values, start, length, factor);
            }
        }
    }

    private static void scaleRow(KernelContext context, FloatArray values, int start, int length, float factor) {
        int i = context.globalIdx;
        if (i < length) {
            values.set(start + i, values.get(start + i) * factor);
        }
    }
}
