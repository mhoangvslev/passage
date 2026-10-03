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
import org.rdfhdt.hdt.enums.TripleComponentRole;
import org.rdfhdt.hdt.triples.impl.BitmapTriples;
import org.rdfhdt.hdt.triples.impl.PredicateIndex;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HDT numbers terms per role: subjects and objects share the identifiers of
 * the terms that are both, and predicates have their own. Passage expects one
 * identifier per term, so this backend exposes global identifiers: subjects,
 * then objects that are not subjects, then predicates. They are converted to
 * the identifiers of the role at search time.
 */
public class HDTBackend implements Backend<Long, String> {

    final HDT hdt;
    final long nbShared;
    final long nbSubjects;
    final long predicateBase; // global identifier of predicate 0
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
        this.nbShared = hdt.getDictionary().getNshared();
        this.nbSubjects = hdt.getDictionary().getNsubjects();
        this.predicateBase = this.nbSubjects + hdt.getDictionary().getNobjects() - this.nbShared;
    }

    /**
     * @return The global identifier of the identifier `id` of `role`.
     */
    long toGlobal(long id, TripleComponentRole role) {
        return switch (role) {
            case SUBJECT -> id;
            case OBJECT -> id <= this.nbShared ? id : this.nbSubjects + id - this.nbShared;
            case PREDICATE -> this.predicateBase + id;
            default -> throw new UnsupportedOperationException(role.toString());
        };
    }

    /**
     * @return The identifier of `role` of the term of the global identifier
     *         `global`, 0 when the term never has this role.
     */
    long fromGlobal(long global, TripleComponentRole role) {
        boolean isSubject = global <= this.nbSubjects;
        boolean isPredicate = global > this.predicateBase;
        long id = switch (role) {
            case SUBJECT -> isSubject ? global : (isPredicate ? -1 : 0);
            case OBJECT -> global <= this.nbShared ? global :
                    (isSubject ? 0 : (isPredicate ? -1 : this.nbShared + global - this.nbSubjects));
            case PREDICATE -> isPredicate ? global - this.predicateBase : -1;
            default -> throw new UnsupportedOperationException(role.toString());
        };
        if (id >= 0) { return id; }
        // An IRI can be both a predicate and a subject or object.
        long other = this.hdt.getDictionary().stringToId(this.getValue(global), role);
        return Math.max(other, 0);
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
        long sId = isAny(s) ? 0L : fromGlobal(s, TripleComponentRole.SUBJECT);
        long pId = isAny(p) ? 0L : fromGlobal(p, TripleComponentRole.PREDICATE);
        long oId = isAny(o) ? 0L : fromGlobal(o, TripleComponentRole.OBJECT);
        if ((!isAny(s) && sId == 0L) || (!isAny(p) && pId == 0L) || (!isAny(o) && oId == 0L)) {
            return BackendIterator.empty(); // a bound term never has its role
        }
        return new HDTIterator(this, sId, pId, oId);
    }

    private static boolean isAny(Long id) {
        return Objects.isNull(id) || id == 0L;
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
        TripleComponentRole role = id <= this.nbSubjects ? TripleComponentRole.SUBJECT :
                (id > this.predicateBase ? TripleComponentRole.PREDICATE : TripleComponentRole.OBJECT);
        return this.hdt.getDictionary().idToString(fromGlobal(id, role), role).toString();
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

    /**
     * @param type (Optional) The role of the term; any role when omitted, e.g. for `VALUES`.
     */
    @Override
    public Long getId(String s, int... type) {
        List<TripleComponentRole> roles = type.length > 0 ?
                List.of(SPOC2TripleComponentRole.toTripleComponentRole(type[0])) :
                List.of(TripleComponentRole.SUBJECT, TripleComponentRole.OBJECT, TripleComponentRole.PREDICATE);
        for (TripleComponentRole role : roles) {
            long id = roleId(s, role);
            if (id > 0) { return toGlobal(id, role); }
        }
        throw new NotFoundException(s);
    }

    private long roleId(String s, TripleComponentRole role) {
        long id = this.hdt.getDictionary().stringToId(s, role);
        if (id > 0) { return id; }
        try {
            return this.hdt.getDictionary().stringToId(NodeValue.parse(s).asString(), role);
        } catch (RiotException re) {
            return 0;
        }
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
