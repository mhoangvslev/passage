package fr.gdd.raw.iterators;

import fr.gdd.passage.commons.utils.MultisetResultChecking;
import fr.gdd.passage.hdt.HDTBackend;
import fr.gdd.passage.hdt.datasets.HDTInMemoryDatasetsFactory;
import fr.gdd.raw.RawOpExecutorUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random walks on HDT, where every step after the first one scans a triple
 * pattern whose subject is bound.
 */
public class RandomHDTTest {

    private static final Logger log = LoggerFactory.getLogger(RandomHDTTest.class);

    @Test
    public void simple_triple_pattern () throws Exception {
        HDTBackend backend = new HDTBackend(HDTInMemoryDatasetsFactory.triples9());
        String queryAsString = "SELECT * WHERE {?s <http://address> ?o}";

        var results = RawOpExecutorUtils.executeWithRaw(queryAsString, backend, 1000L);
        log.debug("{}", results);
        assertEquals(3, results.elementSet().size());
        assertEquals(1000, results.size());
        assertTrue(MultisetResultChecking.containsAllResults(results, List.of("s", "o"),
                List.of("Alice", "nantes"),
                List.of("Bob", "paris"),
                List.of("Carol", "nantes")));
        backend.close();
    }

    @Test
    public void simple_bgp_of_3_tps () throws Exception {
        HDTBackend backend = new HDTBackend(HDTInMemoryDatasetsFactory.triples9());
        String queryAsString = "SELECT * WHERE {?p <http://address> ?c . ?p <http://own> ?a . ?a <http://species> ?s}";

        var results = RawOpExecutorUtils.executeWithRaw(queryAsString, backend, 1000L);
        log.debug("{}", results);
        assertEquals(3, results.elementSet().size());
        assertTrue(MultisetResultChecking.containsAllResults(results, List.of("p", "a", "s"),
                List.of("Alice", "cat", "feline"),
                List.of("Alice", "dog", "canine"),
                List.of("Alice", "snake", "reptile")));
        backend.close();
    }

    @Test
    public void an_object_used_as_subject_is_not_another_subject () throws Exception {
        // HDT numbers subjects and objects apart: the identifier of an object-only
        // term like <http://nantes> is also the one of some subject-only term.
        HDTBackend backend = new HDTBackend(HDTInMemoryDatasetsFactory.triples9());
        String queryAsString = "SELECT * WHERE {?x <http://address> ?c . ?c ?p ?o}";

        var results = RawOpExecutorUtils.executeWithRaw(queryAsString, backend, 1000L);
        log.debug("{}", results);
        assertEquals(0, results.size());
        backend.close();
    }

    @Test
    public void values_bind_terms_whatever_their_position () throws Exception {
        HDTBackend backend = new HDTBackend(HDTInMemoryDatasetsFactory.triples9());
        String queryAsString = """
            SELECT * WHERE {
                VALUES ?a { <http://cat> <http://nantes> }
                ?p <http://own> ?a .
                ?a <http://species> ?s }""";

        var results = RawOpExecutorUtils.executeWithRaw(queryAsString, backend, 1000L);
        log.debug("{}", results);
        assertEquals(1, results.elementSet().size());
        assertTrue(MultisetResultChecking.containsAllResults(results, List.of("p", "a", "s"),
                List.of("Alice", "cat", "feline")));
        backend.close();
    }

    @Test
    public void count_of_carthesian_product_bgp () throws Exception {
        HDTBackend backend = new HDTBackend(HDTInMemoryDatasetsFactory.triples9());
        String queryAsString = """
            SELECT (COUNT(*) AS ?c) WHERE {
                <http://Alice> ?p ?o .
                <http://Alice> <http://own> ?a }""";

        // both scans are uniform with exact probabilities, so 1 walk is 100% accurate.
        var results = RawOpExecutorUtils.executeWithRaw(queryAsString, backend, 1L);
        log.debug("{}", results);
        assertEquals(1, results.size());
        assertTrue(MultisetResultChecking.containsAllResults(results, List.of("c"), List.of("12")));
        backend.close();
    }
}
