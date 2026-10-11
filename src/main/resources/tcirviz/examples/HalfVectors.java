import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.types.HalfFloat;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.collections.VectorFloat4;
import uk.ac.manchester.tornado.api.types.vectors.Float4;

/** Vector types are values in TC IR (vec<4 x f32>); HalfFloat arithmetic rounds through single precision, as Java does. */
public class HalfVectors {

    public static void scale(VectorFloat4 in, VectorFloat4 out, float factor) {
        for (@Parallel int i = 0; i < in.getLength(); i++) {
            Float4 v = in.get(i);
            out.set(i, Float4.mult(v, factor));
        }
    }

    public static void halfAdd(FloatArray a, FloatArray b, FloatArray c) {
        for (@Parallel int i = 0; i < c.getSize(); i++) {
            HalfFloat x = new HalfFloat(a.get(i));
            HalfFloat y = new HalfFloat(b.get(i));
            c.set(i, HalfFloat.add(x, y).getFloat32());
        }
    }
}
