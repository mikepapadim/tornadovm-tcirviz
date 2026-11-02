import uk.ac.manchester.tornado.api.tile.PartitionView;
import uk.ac.manchester.tornado.api.tile.TileContext;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/**
 * The sum of every row, one row per tile block, read as one 1 x 128 tile. CUDA only (CUDA Tile C++).
 *
 * A tile that may run past the edge of its tensor is loaded and stored masked. The Tiles stage keeps the
 * plain, TMA-friendly forms only when every extent of the view is a constant that the tile shape divides.
 * Watch the CUDA Tile source as you set the Arguments:
 *   rows, columns unset        load and store masked (the extents are unknown)
 *   rows = 64, columns = 100   the load masked (128 does not divide 100), the store plain
 *   rows = 64, columns = 256   both plain
 * Without the mask, a 1 x 128 tile over 100 columns would also sum 28 values of the next row.
 */
public class TileRowSums {

    public static void rowSums(TileContext tc, FloatArray in, FloatArray sums, int rows, int columns) {
        PartitionView inView = tc.partition(tc.view(in, rows, columns), 1, 128);
        PartitionView sumView = tc.partition(tc.view(sums, rows, 1), 1, 1);
        int row = tc.bidX();
        sumView.store(tc.sum(inView.load(row, 0), 1), row, 0);
    }
}
