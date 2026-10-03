package fr.gdd.raw.iterators;

import fr.gdd.passage.commons.utils.MultisetResultChecking;
import fr.gdd.passage.hdt.HDTBackend;
import fr.gdd.passage.hdt.HDTIterator;
import fr.gdd.passage.hdt.datasets.HDTInMemoryDatasetsFactory;
import fr.gdd.raw.RawOpExecutorUtils;
import fr.gdd.raw.executor.RawConstants;
import fr.gdd.raw.executor.RawOpExecutor;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.ARQConstants;
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
    public void values_rows_only_extend_the_walks_they_match () throws Exception {
        HDTBackend backend = new HDTBackend(HDTInMemoryDatasetsFactory.triples9());
        for (String queryAsString : List.of("""
            SELECT * WHERE {
                VALUES ?c { <http://nantes> <http://unknown> <http://paris> }
                ?p <http://address> ?c .
                ?p <http://own> ?a .
                ?a <http://species> ?s }""", """
            SELECT * WHERE {
                ?p <http://address> ?c .
                ?p <http://own> ?a .
                ?a <http://species> ?s
                VALUES ?c { <http://nantes> <http://unknown> <http://paris> } }""")) {
            var results = RawOpExecutorUtils.executeWithRaw(queryAsString, backend, 1000L);
            log.debug("{}", results);
            assertEquals(3, results.elementSet().size(), queryAsString);
            assertTrue(MultisetResultChecking.containsAllResults(results, List.of("p", "c", "a"),
                    List.of("Alice", "nantes", "cat"),
                    List.of("Alice", "nantes", "dog"),
                    List.of("Alice", "nantes", "snake")), queryAsString);
        }
        backend.close();
    }

    @Test
    public void a_failed_step_ends_the_walk_of_a_select_query () throws Exception {
        // As served: SELECT queries return failed walks too, each triple pattern
        // becoming an OpLeftJoinFail, so a step after a failed one must not run.
        HDTBackend backend = new HDTBackend(HDTInMemoryDatasetsFactory.triples9());
        String queryAsString = """
            SELECT * WHERE {
                VALUES ?c { <http://nantes> <http://reptile> }
                ?p <http://address> ?c .
                ?p <http://own> ?a }""";
        RawOpExecutor<Long, String> executor = new RawOpExecutor<Long, String>().setBackend(backend);
        executor.getExecutionContext().getContext().set(ARQConstants.sysCurrentQuery, QueryFactory.create(queryAsString));
        executor.getExecutionContext().getContext().set(RawConstants.ATTEMPT_LIMIT, 1000L);

        var results = RawOpExecutorUtils.execute(queryAsString, executor);
        log.debug("{}", results);
        assertTrue(results.stream().anyMatch(r -> r.contains("reptile")));
        assertTrue(results.stream().filter(r -> r.contains("reptile")).noneMatch(r -> r.contains("?p->")), results.toString());
        assertTrue(results.stream().filter(r -> r.contains("?p->")).allMatch(r -> r.contains("nantes")), results.toString());
        backend.close();
    }

    @Test
    public void walks_are_reproducible_with_a_seed () throws Exception {
        HDTBackend backend = new HDTBackend(HDTInMemoryDatasetsFactory.triples9());
        String queryAsString = """
            SELECT * WHERE {
                VALUES ?c { <http://nantes> <http://paris> }
                ?p <http://address> ?c .
                ?p <http://own> ?a }""";
        List<List<String>> runs = new java.util.ArrayList<>();
        for (long seed : new long[]{42L, 42L, 7L}) {
            // As the server does for a `seed` request parameter.
            HDTIterator.RNG.set(new java.util.Random(seed));
            RawOpExecutor<Long, String> executor = new RawOpExecutor<Long, String>().setBackend(backend);
            executor.getExecutionContext().getContext().set(RawConstants.RANDOM, new java.util.Random(seed));
            executor.getExecutionContext().getContext().set(RawConstants.ATTEMPT_LIMIT, 50L);
            List<String> walks = new java.util.ArrayList<>();
            executor.execute(org.apache.jena.sparql.algebra.Algebra.compile(QueryFactory.create(queryAsString)))
                    .forEachRemaining(b -> walks.add(b.toString()));
            runs.add(walks);
        }
        assertEquals(runs.get(0), runs.get(1));
        assertTrue(!runs.get(0).equals(runs.get(2)), "another seed draws other walks");
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
