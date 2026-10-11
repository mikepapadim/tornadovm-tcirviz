import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

/** y = a * x + y. Watch the scalar `alpha` stay a kernel parameter while the arrays become pointers at ABI lowering. */
public class Saxpy {

    public static void saxpy(float alpha, FloatArray x, FloatArray y) {
        for (@Parallel int i = 0; i < y.getSize(); i++) {
            y.set(i, alpha * x.get(i) + y.get(i));
        }
    }
}
