package workorders;

import rife.engine.Context;
import rife.engine.PathInfoHandling;
import rife.engine.Route;
import rife.engine.Router;
import rife.engine.exceptions.EngineException;

import java.io.IOException;

/**
 * The photos and signatures of the orders, which the office's pages and the
 * technician's both show: a group of either site.
 */
public class Photos extends Router {
    private final Store store_;

    Route photo;

    public Photos(Store store) {
        store_ = store;
    }

    public void setup() {
        photo = get("/photo", PathInfoHandling.MAP(m -> m.p("id", "\\d+")), this::serve);
    }

    String url(Context c, Photo shown) {
        return c.urlFor(photo).param("id", shown.getId()).toString();
    }

    private void serve(Context c) {
        var found = store_.photo(c.parameterInt("id"));
        if (null == found) {
            c.setStatus(404);
            return;
        }
        // a photo never changes, a new one gets a new id
        c.setHeader("Cache-Control", "private, max-age=31536000, immutable");
        // even an image that isn't one can't run anything here
        c.setHeader("X-Content-Type-Options", "nosniff");
        c.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        c.setContentType(Photo.isAccepted(found.getKind(), found.getContentType()) ? found.getContentType() : "application/octet-stream");
        try {
            c.outputStream().write(found.getData());
        } catch (IOException e) {
            throw new EngineException(e);
        }
    }
}
