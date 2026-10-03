package fr.gdd.passage.hdt;

import fr.gdd.passage.commons.interfaces.SPOC;
import fr.gdd.passage.commons.utils.InMemoryStatements;
import fr.gdd.passage.hdt.datasets.HDTInMemoryDatasetsFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Random walks need `random()` and `cardinality()` on every triple pattern,
 * not only on those where hdt-java allows `goTo`.
 */
class HDTRandomTest {

    static final int DRAWS = 2000;

    // Bob also owns the cat, so `?s own ?o` is not uniform: Alice owns 3 animals.
    static final List<String> statements = Stream.concat(InMemoryStatements.triples9.stream(),
            Stream.of("<http://Bob> <http://own> <http://cat> .")).toList();

    HDTBackend backend;
    long alice, bob, own, address, cat, nantes;

    @BeforeEach
    public void setup() {
        HDTIterator.RNG = ThreadLocal.withInitial(() -> new Random(42));
        backend = new HDTBackend(HDTInMemoryDatasetsFactory.getDataset(statements));
        alice = backend.getId("<http://Alice>", SPOC.SUBJECT);
        bob = backend.getId("<http://Bob>", SPOC.SUBJECT);
        own = backend.getId("<http://own>", SPOC.PREDICATE);
        address = backend.getId("<http://address>", SPOC.PREDICATE);
        cat = backend.getId("<http://cat>", SPOC.OBJECT);
        nantes = backend.getId("<http://nantes>", SPOC.OBJECT);
    }

    @Test
    public void random_on_every_pattern_returns_matching_triples_with_exact_probabilities() {
        long any = backend.any();
        List<long[]> patterns = List.of(
                new long[]{any, any, any},
                new long[]{alice, any, any},
                new long[]{alice, own, any},
                new long[]{alice, own, cat},
                new long[]{alice, any, cat},
                new long[]{any, own, any},
                new long[]{any, address, nantes},
                new long[]{any, any, cat});
        for (long[] spo : patterns) {
            String name = Arrays.toString(spo);
            Set<List<Long>> expected = scan(spo);
            assertFalse(expected.isEmpty(), name);
            assertEquals(expected.size(), search(spo).cardinality(), name);

            Set<List<Long>> drawn = new HashSet<>();
            double estimate = 0.;
            HDTIterator it = search(spo);
            for (int i = 0; i < DRAWS; ++i) {
                double probability = it.random();
                it.next();
                List<Long> triple = triple(it);
                assertTrue(expected.contains(triple), name + " drew " + triple);
                assertEquals(expectedProbability(spo, triple, expected), probability, 1e-12, name);
                drawn.add(triple);
                estimate += 1. / probability;
            }
            assertEquals(expected, drawn, name);
            assertEquals(expected.size(), estimate / DRAWS, 0.1 * expected.size(), name);
        }
    }

    HDTIterator search(long[] spo) {
        return (HDTIterator) backend.search(spo[0], spo[1], spo[2]);
    }

    Set<List<Long>> scan(long[] spo) {
        Set<List<Long>> triples = new HashSet<>();
        HDTIterator it = search(spo);
        while (it.hasNext()) {
            it.next();
            triples.add(triple(it));
        }
        return triples;
    }

    static List<Long> triple(HDTIterator it) {
        return List.of(it.getId(SPOC.SUBJECT), it.getId(SPOC.PREDICATE), it.getId(SPOC.OBJECT));
    }

    /**
     * Uniform, except for `?P?`, which draws a subject, then one of its objects.
     */
    double expectedProbability(long[] spo, List<Long> triple, Set<List<Long>> expected) {
        long any = backend.any();
        if (spo[0] != any || spo[1] == any || spo[2] != any) {
            return 1. / expected.size();
        }
        long nbSubjects = expected.stream().map(t -> t.get(0)).distinct().count();
        long degree = expected.stream().filter(t -> t.get(0).equals(triple.get(0))).count();
        return 1. / nbSubjects / degree;
    }
}
