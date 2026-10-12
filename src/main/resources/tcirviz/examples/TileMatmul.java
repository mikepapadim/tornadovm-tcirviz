import uk.ac.manchester.tornado.api.tile.DType;
import uk.ac.manchester.tornado.api.tile.PartitionView;
import uk.ac.manchester.tornado.api.tile.Tile;
import uk.ac.manchester.tornado.api.tile.TileContext;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.HalfFloatArray;

/**
 * C = A * B with TileContext: each tile block owns one TILE x TILE block of C and accumulates it with
 * mma over the K dimension. CUDA only (CUDA Tile C++). The Tiles stage gives every view and tile its
 * static shape; set m, n and k in Arguments to see the views' extents and the loop bound fold.
 */
public class TileMatmul {

    private static final int TILE = 32;

    public static void matmul(TileContext tc, HalfFloatArray a, HalfFloatArray b, FloatArray c, int m, int n, int k) {
        PartitionView aView = tc.partition(tc.view(a, m, k), TILE, TILE);
        PartitionView bView = tc.partition(tc.view(b, k, n), TILE, TILE);
        PartitionView cView = tc.partition(tc.view(c, m, n), TILE, TILE);

        int row = tc.bidX();
        int column = tc.bidY();

        Tile acc = tc.zeros(DType.F32, TILE, TILE);
        for (int step = 0; step < k / TILE; step++) {
            acc = tc.mma(aView.load(row, step), bView.load(step, column), acc);
        }
        cView.store(acc, row, column);
    }
}
