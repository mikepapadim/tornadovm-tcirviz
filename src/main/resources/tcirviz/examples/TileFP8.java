import uk.ac.manchester.tornado.api.tile.DType;
import uk.ac.manchester.tornado.api.tile.PartitionView;
import uk.ac.manchester.tornado.api.tile.Tile;
import uk.ac.manchester.tornado.api.tile.TileContext;
import uk.ac.manchester.tornado.api.types.arrays.FP8Array;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * FP8 (E4M3) tiles: scale, add and compare. CUDA only (CUDA Tile C++), and FP8 tiles need compute
 * capability 9.0: on sm_89 TC refuses the kernel (TC-E4011); pick 90 in the CUDA target options.
 *
 * CUDA Tile has no FP8 arithmetic, so in the CUDA source every operation widens its operands to float
 * and rounds the result back with ct::element_cast<__nv_fp8_e4m3>: chained operations see FP8 values,
 * as the Java API defines them. The scale factor 1.25 rounds to FP8 too, through a float tile
 * (ct::full) cast to FP8, since scalar FP8 conversions cannot be called from a tile kernel. The
 * compare widens x to float and compares it with the unrounded 0.3.
 */
public class TileFP8 {

    private static final int SIZE = 32;

    public static void scaleAndCompare(TileContext tc, FP8Array a, FP8Array b, FP8Array out, FloatArray above) {
        PartitionView av = tc.partition(tc.view(a, DType.FP8_E4M3, SIZE), SIZE);
        PartitionView bv = tc.partition(tc.view(b, DType.FP8_E4M3, SIZE), SIZE);
        PartitionView ov = tc.partition(tc.view(out, DType.FP8_E4M3, SIZE * 2), SIZE);
        PartitionView fv = tc.partition(tc.view(above, SIZE), SIZE);
        Tile x = av.load(0);
        Tile y = bv.load(0);
        ov.store(tc.scale(x, 1.25), 0);
        ov.store(tc.add(x, y), 1);
        fv.store(tc.select(tc.greaterThan(x, 0.3), tc.ones(DType.F32, SIZE), tc.zeros(DType.F32, SIZE)), 0);
    }
}
