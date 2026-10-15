import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Per-thread private arrays. Each thread scans a row in segments and keeps the largest count of any value
 * in 0..3 seen in one segment. TC declares both arrays once, at the top of the kernel, but Java gives a
 * fresh zeroed array every time {@code new} runs: counts is allocated once per segment, and best once
 * per row (the @Parallel loop becomes a grid-stride loop, so one thread may handle several rows). Follow
 * {@code zero best;} and {@code zero counts;} from the structured IR into each target's zeroing loop.
 */
public class PrivateArrays {

    public static void segmentModes(IntArray values, IntArray out, int rowLength, int segment) {
        for (@Parallel int row = 0; row < out.getSize(); row++) {
            int[] best = new int[1];
            for (int start = 0; start < rowLength; start += segment) {
                int[] counts = new int[4];
                for (int k = start; k < start + segment && k < rowLength; k++) {
                    counts[values.get(row * rowLength + k) & 3]++;
                }
                for (int b = 0; b < 4; b++) {
                    best[0] = Math.max(best[0], counts[b]);
                }
            }
            out.set(row, best[0]);
        }
    }
}
