package fr.gdd.passage.hdt;

import fr.gdd.passage.commons.exceptions.NotFoundException;
import fr.gdd.passage.commons.interfaces.Backend;
import fr.gdd.passage.commons.interfaces.BackendIterator;
import org.apache.jena.riot.RiotException;
import org.apache.jena.sparql.expr.NodeValue;
import org.rdfhdt.hdt.hdt.HDT;
import org.rdfhdt.hdt.hdt.HDTManager;

import org.rdfhdt.hdt.compact.bitmap.AdjacencyList;
import org.rdfhdt.hdt.enums.TripleComponentOrder;
import org.rdfhdt.hdt.triples.impl.BitmapTriples;
import org.rdfhdt.hdt.triples.impl.PredicateIndex;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class HDTBackend implements Backend<Long, String> {

    final HDT hdt;
    final BitmapTriples triples;
    final AdjacencyList adjY;
    final AdjacencyList adjZ;
    final ConcurrentHashMap<Long, Long> predicateCardinalities = new ConcurrentHashMap<>();

    /**
     * @param path The path to the HDT file, memory-mapped along with its
     *             `.index.v1-1` (generated next to it when missing).
     */
    public HDTBackend(String path) throws IOException {
        this(HDTManager.mapIndexedHDT(path));
    }

    public HDTBackend(HDT hdt) {
        this.hdt = hdt;
        this.triples = (BitmapTriples) hdt.getTriples();
        if (this.triples.getOrder() != TripleComponentOrder.SPO) {
            throw new UnsupportedOperationException("Only SPO-ordered HDT files are supported.");
        }
        this.adjY = new AdjacencyList(this.triples.getSeqY(), this.triples.getBitmapY());
        this.adjZ = new AdjacencyList(this.triples.getSeqZ(), this.triples.getBitmapZ());
    }

    /**
     * @param s The subject identifier.
     * @param p The predicate identifier, or `any()`.
     * @return The range `[from, to)` of positions in the SPO bitmaps that
     *         contains every triple of `s` (with `p`), empty when there are none.
     */
    long[] subjectRange(long s, long p) {
        if (s > this.adjY.countListsX()) { return new long[]{0L, 0L}; }
        long minY, maxY;
        if (p != any()) {
            minY = this.adjY.find(s - 1, p);
            if (minY < 0) { return new long[]{0L, 0L}; }
            maxY = minY + 1;
        } else {
            minY = this.adjY.find(s - 1);
            maxY = this.adjY.last(s - 1) + 1;
        }
        return new long[]{this.adjZ.find(minY), this.adjZ.last(maxY - 1) + 1};
    }

    /**
     * @param p The predicate identifier.
     * @return The exact number of triples with predicate `p`; computed once,
     *         since HDT only stores the number of subjects having `p`.
     */
    long predicateCardinality(long p) {
        return this.predicateCardinalities.computeIfAbsent(p, ignored -> {
            PredicateIndex index = this.triples.getPredicateIndex();
            long base = index.getBase(p);
            long cardinality = 0L;
            for (long occurrence = 1; occurrence <= index.getNumOcurrences(p); ++occurrence) {
                long posY = index.getOccurrence(base, occurrence);
                cardinality += this.adjZ.last(posY) + 1 - this.adjZ.find(posY);
            }
            return cardinality;
        });
    }

    @Override
    public BackendIterator<Long, String> search(Long s, Long p, Long o) {
        return new HDTIterator(this,
                Objects.isNull(s) ? any() : s,
                Objects.isNull(p) ? any() : p,
                Objects.isNull(o) ? any() : o);
    }

    @Override
    public BackendIterator<Long, String> search(Long s, Long p, Long o, Long c) {
        throw new UnsupportedOperationException("HDT does not support quads.");
    }

    @Override
    public Long any() {
        return 0L;
    }

    @Override
    public String getValue(Long id, int... type) {
        return this.hdt.getDictionary().idToString(id, SPOC2TripleComponentRole.toTripleComponentRole(type[0])).toString();
    }

    @Override
    public String getString(Long id, int... type) {
        return toNTriples(this.getValue(id, type));
    }

    /**
     * @param value A term of the HDT dictionary, which stores IRIs without brackets.
     * @return The term as in N-Triples, as returned by the other backends.
     */
    static String toNTriples(String value) {
        return value.startsWith("\"") || value.startsWith("_:") || value.startsWith("<") ? value : "<" + value + ">";
    }

    @Override
    public Long getId(String s, int... type) {
        long id = this.hdt.getDictionary().stringToId(s, SPOC2TripleComponentRole.toTripleComponentRole(type[0]));
        if (id <= 0) {
            try {
                NodeValue nv = NodeValue.parse(s);
                id = this.hdt.getDictionary().stringToId(nv.asString(), SPOC2TripleComponentRole.toTripleComponentRole(type[0]));
                if (id <= 0) {
                    throw new NotFoundException(s);
                }
            } catch (RiotException re) {
                throw new NotFoundException(s);
            }
        }
        return id;
    }

    @Override
    public String getValue(String value, int... type) {
        return value;
    }

    @Override
    public void close() throws Exception {
        this.hdt.close();
    }
}
