package io.github.search5.hg4j.api;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code sortsource}, {@code adjustdest} and {@code defineparents} of Mercurial 7.2 ({@code hgext/rebase.py}) run on random graphs
 * (merges and forks) built with {@code hg debugbuilddag}; every line of the fixture is an answer of the original.
 */
class RebasePlannerVectorsTest {

    private static Map<Integer, Integer> map(String text) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (!text.isEmpty()) {
            for (String kv : text.split(",")) {
                String[] p = kv.split("=");
                out.put(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            }
        }
        return out;
    }

    private static Set<Integer> set(String text) {
        Set<Integer> out = new HashSet<>();
        if (!text.isEmpty()) {
            for (String s : text.split(",")) {
                out.add(Integer.parseInt(s));
            }
        }
        return out;
    }

    /** Names are `rev:hash` in the original; the planner is given an empty name, so the hash is dropped on the expected side. */
    private static String normalized(String message) {
        return message.replaceAll("(\\d+):[0-9a-f]{12}", "$1:");
    }

    @Test
    void everyAnswerOfTheOriginalIsReproduced() throws Exception {
        Map<String, RebasePlanner> graphs = new HashMap<>();
        int checked = 0;
        int errors = 0;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/shape/rebase-parents-vectors.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f[0].equals("G")) {
                    List<int[]> parents = new ArrayList<>();
                    for (String pp : f[2].split(";")) {
                        String[] ab = pp.split(",");
                        parents.add(new int[]{Integer.parseInt(ab[0]), Integer.parseInt(ab[1])});
                    }
                    graphs.put(f[1], new RebasePlanner(new RebasePlanner.Graph() {
                        public int size() {
                            return parents.size();
                        }

                        public int[] parents(int rev) {
                            return parents.get(rev);
                        }
                    }).withNamer(r -> ""));
                    continue;
                }
                RebasePlanner planner = graphs.get(f[1]);
                String where = line;
                switch (f[0]) {
                    case "S" -> {
                        String expected = f[3];
                        String actual;
                        try {
                            StringBuilder sb = new StringBuilder();
                            for (List<Integer> b : RebasePlanner.sortSource(map(f[2]))) {
                                if (sb.length() > 0) {
                                    sb.append(';');
                                }
                                sb.append(String.join(",", b.stream().map(String::valueOf).toList()));
                            }
                            actual = sb.toString();
                        } catch (Exception e) {
                            actual = "ERR:" + e.getMessage();
                        }
                        assertEquals(expected, actual, where);
                    }
                    case "A" -> {
                        String actual;
                        try {
                            int[] r = planner.adjustDest(Integer.parseInt(f[2]), map(f[3]), map(f[4]), set(f[5]));
                            actual = r[0] + "," + r[1];
                        } catch (Exception e) {
                            actual = "ERR:" + e.getMessage();
                        }
                        assertEquals(normalized(f[6]), actual, where);
                    }
                    case "P" -> {
                        String actual;
                        try {
                            RebasePlanner.Parents r = planner.defineParents(Integer.parseInt(f[2]), map(f[3]), map(f[4]), set(f[5]), Set.of());
                            actual = r.p1() + "," + r.p2() + "," + r.base();
                        } catch (Exception e) {
                            actual = "ERR:" + e.getMessage();
                        }
                        assertEquals(normalized(f[6]), actual, where);
                    }
                    default -> fail(line);
                }
                checked++;
                errors += f[f.length - 1].startsWith("ERR") ? 1 : 0;
            }
        }
        assertTrue(checked > 4000, "vectors: " + checked);
        assertTrue(errors > 100, "the error cases are covered: " + errors);
    }

    @Test
    void sortMinusTopoGivesTheOrderOfTheOriginal() throws Exception {
        Map<String, RebasePlanner> graphs = new HashMap<>();
        int checked = 0;
        int different = 0;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/shape/rebase-topo-vectors.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f[0].equals("G")) {
                    List<int[]> parents = new ArrayList<>();
                    for (String pp : f[2].split(";")) {
                        String[] ab = pp.split(",");
                        parents.add(new int[]{Integer.parseInt(ab[0]), Integer.parseInt(ab[1])});
                    }
                    graphs.put(f[1], new RebasePlanner(new RebasePlanner.Graph() {
                        public int size() {
                            return parents.size();
                        }

                        public int[] parents(int rev) {
                            return parents.get(rev);
                        }
                    }));
                    continue;
                }
                List<Integer> subset = new ArrayList<>();
                for (String r : f[2].split(",")) {
                    subset.add(Integer.parseInt(r));
                }
                String actual = String.join(",", graphs.get(f[1]).topoOrder(subset).stream().map(String::valueOf).toList());
                assertEquals(f[3], actual, line);
                checked++;
                different += f[2].equals(f[3]) ? 0 : 1;
            }
        }
        assertTrue(checked > 100, "vectors: " + checked);
        System.out.println("topo vectors checked: " + checked + ", with an order other than ascending: " + different);
    }
}
