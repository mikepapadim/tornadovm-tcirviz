import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/** The "hello world" of GPU programming: one thread per element. */
public class VectorAdd {

    public static void add(FloatArray a, FloatArray b, FloatArray c) {
        for (@Parallel int i = 0; i < c.getSize(); i++) {
            float x = a.get(i);
            float y = b.get(i);
            c.set(i, x + y);
        }
    }
}
