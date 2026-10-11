import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/** A 2D image filter: nested loops, Math.min/max clamping and integer index arithmetic. */
public class BlurFilter {

    public static void blur(IntArray image, IntArray out, FloatArray filter, int width, int height, int filterWidth) {
        for (@Parallel int row = 0; row < height; row++) {
            for (@Parallel int col = 0; col < width; col++) {
                float sum = 0.0f;
                for (int fr = -filterWidth / 2; fr <= filterWidth / 2; fr++) {
                    for (int fc = -filterWidth / 2; fc <= filterWidth / 2; fc++) {
                        int r = Math.min(Math.max(row + fr, 0), height - 1);
                        int c = Math.min(Math.max(col + fc, 0), width - 1);
                        float weight = filter.get((fr + filterWidth / 2) * filterWidth + fc + filterWidth / 2);
                        sum += image.get(r * width + c) * weight;
                    }
                }
                out.set(row * width + col, (int) sum);
            }
        }
    }
}
