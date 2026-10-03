package fr.gdd.passage.hdt;

import fr.gdd.passage.commons.exceptions.UndefinedCode;
import fr.gdd.passage.commons.interfaces.BackendIterator;
import fr.gdd.passage.commons.interfaces.SPOC;
import org.rdfhdt.hdt.enums.TripleComponentRole;
import org.rdfhdt.hdt.triples.IteratorTripleID;
import org.rdfhdt.hdt.triples.TripleID;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class HDTIterator  extends BackendIterator<Long, String> {
    public static ThreadLocal<Random> RNG = ThreadLocal.withInitial(() -> {
        // Seed can be derived from a common seed or generated uniquely per thread
        long seed = System.nanoTime() + Thread.currentThread().threadId();
        return new Random(seed);
    });


    final HDTBackend backend;
    final TripleID start;

    IteratorTripleID iterator;
    IteratorTripleID positioned; // read by `next()`: `iterator`, or a `???` one placed by `random()`
    TripleID current;
    long offset = 0L;

    public HDTIterator(HDTBackend backend, Long s, Long p, Long o) {
        this.backend = backend;
        this.start = new TripleID(s, p, o);
        this.iterator = this.backend.hdt.getTriples().search(start);
        this.positioned = this.iterator;
    }

    @Override
    public Long getId(int code) {
        return switch (code) {
            case SPOC.SUBJECT -> backend.toGlobal(current.getSubject(), TripleComponentRole.SUBJECT);
            case SPOC.PREDICATE -> backend.toGlobal(current.getPredicate(), TripleComponentRole.PREDICATE);
            case SPOC.OBJECT -> backend.toGlobal(current.getObject(), TripleComponentRole.OBJECT);
            default -> throw new UndefinedCode(code);
        };
    }

    @Override
    public String getValue(int code) {
        return backend.getValue(this.getId(code), code);
    }

    @Override
    public String getString(int code) {
        return backend.getString(this.getId(code), code);
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public void next() {
        current = positioned.next();
    }

    @Override
    public void reset() {
        this.iterator = this.backend.hdt.getTriples().search(this.start);
        this.positioned = this.iterator;
        this.offset = 0L;
        this.current = null;
    }


    /**
     * As of 20dec of 2024, only a few skips work with the index:
     * `?s ?p ?o`, `?s P O` and `?s ?p O`.
     * The rest of indexes we still process by calling next until
     * the offset is reached.
     * @param to The cursor location to skip to.
     */
    @Override
    public void skip(long to) {
        if (this.iterator.canGoTo()) {
            this.iterator.goTo(to);
            this.positioned = this.iterator;
            this.offset = to;
        } else {
            while (to > this.offset && this.iterator.hasNext()) {
                this.iterator.next();
                ++this.offset;
            }
        }
        this.current = null;
    }

    @Override
    public long current() {
        return this.offset;
    }

    @Override
    public long previous() {
        return this.offset - 1;
    }

    /**
     * Positions the iterator on a random triple matching the pattern. hdt-java only
     * allows `goTo` on `???`, `?PO`, and `??O`; for the other patterns, the range of
     * matching positions is computed from the SPO bitmaps, then a `???` iterator is
     * placed at a random position in it.
     * @return The probability to choose this triple.
     */
    @Override
    public Double random() {
        this.current = null;
        Random rng = RNG.get();
        if (this.iterator.canGoTo()) {
            long cardinality = this.iterator.estimatedNumResults();
            this.iterator.goTo(rng.nextLong(cardinality));
            this.positioned = this.iterator;
            return 1. / cardinality;
        }

        long s = start.getSubject(), p = start.getPredicate(), o = start.getObject();
        long position;
        double probability;
        if (s != backend.any() && o == backend.any()) { // S??, SP?
            long[] range = backend.subjectRange(s, p);
            position = range[0] + rng.nextLong(range[1] - range[0]);
            probability = 1. / (range[1] - range[0]);
        } else if (s != backend.any()) { // S?O, SPO
            List<Long> matches = objectMatches();
            position = matches.get(rng.nextInt(matches.size()));
            probability = 1. / matches.size();
        } else if (p != backend.any() && o == backend.any()) { // ?P?: a random subject, then a random object of it
            var index = backend.triples.getPredicateIndex();
            long nbSubjects = index.getNumOcurrences(p);
            long posY = index.getOccurrence(index.getBase(p), 1 + rng.nextLong(nbSubjects));
            long from = backend.adjZ.find(posY), to = backend.adjZ.last(posY) + 1;
            position = from + rng.nextLong(to - from);
            probability = 1. / nbSubjects / (to - from);
        } else { // ?PO, ??O without the object index
            throw new UnsupportedOperationException("Random on " + start.getPatternString() + " requires an indexed HDT.");
        }
        this.positioned = backend.triples.searchAll();
        this.positioned.goTo(position);
        return probability;
    }

    /**
     * @return The positions of the triples of the subject (and predicate)
     *         whose object is the one of the pattern.
     */
    private List<Long> objectMatches() {
        long[] range = backend.subjectRange(start.getSubject(), start.getPredicate());
        List<Long> matches = new ArrayList<>();
        for (long position = range[0]; position < range[1]; ++position) {
            if (backend.adjZ.get(position) == start.getObject()) { matches.add(position); }
        }
        return matches;
    }

    /**
     * @return The exact number of triples matching the pattern. hdt-java returns an
     *         upper bound for `S?O`, and the number of subjects for `?P?`.
     */
    @Override
    public double cardinality() throws UnsupportedOperationException {
        long s = start.getSubject(), p = start.getPredicate(), o = start.getObject();
        if (s != backend.any() && p == backend.any() && o != backend.any()) {
            return objectMatches().size();
        }
        if (s == backend.any() && p != backend.any() && o == backend.any()) {
            return backend.predicateCardinality(p);
        }
        return this.iterator.estimatedNumResults();
    }

    @Override
    public double cardinality(long strength) throws UnsupportedOperationException {
        return cardinality();
    }
}
