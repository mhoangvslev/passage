package fr.gdd.passage.cli.assemblers;

import fr.gdd.passage.cli.vocabularies.PassageVocabulary;
import fr.gdd.passage.hdt.HDTBackend;
import fr.gdd.passage.hdt.HDTBackendFactory;
import fr.gdd.passage.hdt.HDTGraph;
import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.assembler.assemblers.AssemblerBase;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;

import static org.apache.jena.sparql.util.graph.GraphUtils.exactlyOneProperty;
import static org.apache.jena.sparql.util.graph.GraphUtils.getStringValue;

/**
 * Graph of `psg:GraphHDT`, whose location is an HDT file, queried by the standard
 * SPARQL engine of Jena, e.g. as the default or a named graph of a `ja:RDFDataset`.
 * It shares the memory-mapped file with the `psg:DatasetHDT` of the same location.
 */
public class GraphAssemblerHDT extends AssemblerBase {

    @Override
    public Model open(Assembler a, Resource root, Mode mode) {
        exactlyOneProperty(root, PassageVocabulary.location);
        String location = getStringValue(root, PassageVocabulary.location);
        HDTBackend backend = (HDTBackend) DatasetAssemblerBackend.manager.addBackend(location, new HDTBackendFactory());
        return ModelFactory.createModelForGraph(new HDTGraph(backend));
    }
}
