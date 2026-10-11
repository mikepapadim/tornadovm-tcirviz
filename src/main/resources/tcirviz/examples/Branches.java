import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Control flow: a switch, a ternary, short-circuit && and an early continue. In TC IR they are
 * blocks and branches; the structurizer turns them back into switch / if / continue.
 */
public class Branches {

    public static void classify(IntArray in, IntArray out) {
        for (@Parallel int i = 0; i < in.getSize(); i++) {
            int v = in.get(i);
            if (v < 0 && v != Integer.MIN_VALUE) {
                out.set(i, -v);
                continue;
            }
            int r;
            switch (v % 4) {
                case 0 -> r = v >>> 1;
                case 1 -> r = v * 3 + 1;
                case 2 -> r = v > 100 ? 100 : v;
                default -> r = 0;
            }
            out.set(i, r);
        }
    }
}
