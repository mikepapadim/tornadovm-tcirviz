import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.ShortArray;

/** A while loop with a data-dependent exit inside every thread: see how the structurizer rebuilds it. */
public class Mandelbrot {

    public static void mandelbrot(int size, ShortArray output) {
        final int iterations = 10000;
        float space = 2.0f / size;
        for (@Parallel int i = 0; i < size; i++) {
            for (@Parallel int j = 0; j < size; j++) {
                float zr = 0.0f;
                float zi = 0.0f;
                float cr = space * i - 1.5f;
                float ci = space * j - 1.0f;
                int y = 0;
                while (y < iterations && zr * zr + zi * zi <= 4.0f) {
                    float tmp = zr * zr - zi * zi + cr;
                    zi = 2.0f * zr * zi + ci;
                    zr = tmp;
                    y++;
                }
                output.set(i * size + j, (short) ((y * 255) / iterations));
            }
        }
    }
}
