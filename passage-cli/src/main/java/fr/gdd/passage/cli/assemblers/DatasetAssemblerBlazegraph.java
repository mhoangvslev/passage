package fr.gdd.passage.cli.assemblers;

import fr.gdd.passage.blazegraph.BlazegraphBackendFactory;
import fr.gdd.passage.cli.server.BlazegraphOpExecutorFactory;
import fr.gdd.passage.commons.interfaces.BackendFactory;
import org.apache.jena.sparql.engine.main.OpExecutorFactory;

/**
 * Dataset of `psg:DatasetBlazegraph`, whose location is a Blazegraph properties file.
 */
public class DatasetAssemblerBlazegraph extends DatasetAssemblerBackend {

    @Override
    protected BackendFactory<?,?,?> backendFactory() { return new BlazegraphBackendFactory(); }

    @Override
    protected OpExecutorFactory sparqlEngine() { return new BlazegraphOpExecutorFactory(); }
}
