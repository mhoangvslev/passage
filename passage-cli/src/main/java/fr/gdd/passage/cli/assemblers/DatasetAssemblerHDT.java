package fr.gdd.passage.cli.assemblers;

import fr.gdd.passage.commons.interfaces.BackendFactory;
import fr.gdd.passage.hdt.HDTBackendFactory;
import org.apache.jena.sparql.engine.main.OpExecutorFactory;

/**
 * Dataset of `psg:DatasetHDT`, whose location is an HDT file. Only `psg:PassageEngine`
 * and `psg:RawEngine` are available, as HDT does not provide a SPARQL engine; for
 * standard SPARQL, use a `ja:RDFDataset` over `psg:GraphHDT` graphs instead.
 */
public class DatasetAssemblerHDT extends DatasetAssemblerBackend {

    @Override
    protected BackendFactory<?,?,?> backendFactory() { return new HDTBackendFactory(); }

    @Override
    protected OpExecutorFactory sparqlEngine() {
        throw new UnsupportedOperationException("psg:SPARQLEngine is not available on psg:DatasetHDT; use a ja:RDFDataset over psg:GraphHDT.");
    }
}
