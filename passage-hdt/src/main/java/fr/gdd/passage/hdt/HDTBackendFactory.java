package fr.gdd.passage.hdt;

import fr.gdd.passage.commons.interfaces.Backend;
import fr.gdd.passage.commons.interfaces.BackendFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public class HDTBackendFactory implements BackendFactory<Long, String, Long> {

    @Override
    public Backend<Long, String> get(Path path) {
        try {
            return new HDTBackend(path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
