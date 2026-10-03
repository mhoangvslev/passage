package fr.gdd.passage.hdt;

import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphBase;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;
import org.apache.jena.util.iterator.WrappedIterator;
import org.rdfhdt.hdt.enums.TripleComponentRole;
import org.rdfhdt.hdt.exceptions.NotFoundException;
import org.rdfhdt.hdt.hdt.HDT;
import org.rdfhdt.hdt.triples.IteratorTripleString;
import org.rdfhdt.hdt.triples.TripleString;

import java.util.Iterator;

/**
 * Read-only Jena graph over an HDT file, so that the standard SPARQL engine of
 * Jena can query it, e.g. as a named graph of a `ja:RDFDataset`.
 * The HDT dictionary stores IRIs without brackets, blank nodes as `_:label`, and
 * literals as `"lexical"`, `"lexical"@lang` or `"lexical"^^<datatype>`.
 */
public class HDTGraph extends GraphBase {

    static final String XSD_STRING = XSDDatatype.XSDstring.getURI();

    final HDT hdt;

    public HDTGraph(HDT hdt) { this.hdt = hdt; }

    public HDTGraph(HDTBackend backend) { this(backend.hdt); }

    @Override
    protected ExtendedIterator<Triple> graphBaseFind(Triple pattern) {
        String s = toHDT(pattern.getSubject(), TripleComponentRole.SUBJECT);
        String p = toHDT(pattern.getPredicate(), TripleComponentRole.PREDICATE);
        String o = toHDT(pattern.getObject(), TripleComponentRole.OBJECT);
        if (s == null || p == null || o == null) { return NiceIterator.emptyIterator(); }
        IteratorTripleString matches;
        try {
            matches = this.hdt.search(s, p, o);
        } catch (NotFoundException e) {
            return NiceIterator.emptyIterator();
        }
        Iterator<Triple> triples = new Iterator<>() {
            @Override public boolean hasNext() { return matches.hasNext(); }
            @Override public Triple next() {
                TripleString t = matches.next();
                return Triple.create(toNode(t.getSubject()), toNode(t.getPredicate()), toNode(t.getObject()));
            }
        };
        return WrappedIterator.create(triples);
    }

    @Override
    protected int graphBaseSize() {
        return (int) Math.min(Integer.MAX_VALUE, this.hdt.getTriples().getNumberOfElements());
    }

    /**
     * @return The term of `node` in the HDT dictionary, "" for a variable, `null`
     *         when the term is absent from the dictionary in this role.
     */
    String toHDT(Node node, TripleComponentRole role) {
        if (node == null || !node.isConcrete()) { return ""; }
        if (node.isURI()) { return node.getURI(); }
        if (node.isBlank()) { return "_:" + node.getBlankNodeLabel(); }
        String quoted = '"' + node.getLiteralLexicalForm() + '"';
        if (!node.getLiteralLanguage().isEmpty()) { return quoted + "@" + node.getLiteralLanguage(); }
        String datatype = node.getLiteralDatatypeURI();
        if (!XSD_STRING.equals(datatype)) { return quoted + "^^<" + datatype + ">"; }
        // xsd:string is implicit in RDF 1.1, so it is stored either way.
        if (this.hdt.getDictionary().stringToId(quoted, role) > 0) { return quoted; }
        String explicit = quoted + "^^<" + XSD_STRING + ">";
        return this.hdt.getDictionary().stringToId(explicit, role) > 0 ? explicit : null;
    }

    static Node toNode(CharSequence term) {
        String s = term.toString();
        if (s.startsWith("_:")) { return NodeFactory.createBlankNode(s.substring(2)); }
        if (!s.startsWith("\"")) { return NodeFactory.createURI(s); }
        int end = s.lastIndexOf('"');
        String lexical = s.substring(1, end);
        String suffix = s.substring(end + 1);
        if (suffix.startsWith("@")) { return NodeFactory.createLiteralLang(lexical, suffix.substring(1)); }
        if (suffix.startsWith("^^<")) {
            String datatype = suffix.substring(3, suffix.length() - 1);
            return NodeFactory.createLiteralDT(lexical, TypeMapper.getInstance().getSafeTypeByName(datatype));
        }
        return NodeFactory.createLiteralString(lexical);
    }
}
