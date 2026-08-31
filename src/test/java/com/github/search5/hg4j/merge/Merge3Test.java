package com.github.search5.hg4j.merge;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Merge3Test {

    @Test
    public void testCleanMerge() {
        List<String> base = List.of("Line 1", "Line 2", "Line 3");
        List<String> yours = List.of("Line 1", "Line 2 [MODIFIED]", "Line 3");
        List<String> theirs = List.of("Line 1", "Line 2", "Line 3", "Line 4 [ADDED]");

        Merge3.MergeResult res = Merge3.merge(base, yours, theirs);
        assertFalse(res.isConflicted());
        
        List<String> expected = List.of(
            "Line 1", 
            "Line 2 [MODIFIED]", 
            "Line 3", 
            "Line 4 [ADDED]"
        );
        assertEquals(expected, res.getMergedLines());
    }

    @Test
    public void testIdenticalEdits() {
        List<String> base = List.of("Line 1", "Line 2", "Line 3");
        List<String> yours = List.of("Line 1", "Line 2 [MOD]", "Line 3");
        List<String> theirs = List.of("Line 1", "Line 2 [MOD]", "Line 3");

        Merge3.MergeResult res = Merge3.merge(base, yours, theirs);
        assertFalse(res.isConflicted());
        assertEquals(yours, res.getMergedLines());
    }

    @Test
    public void testMergeConflict() {
        List<String> base = List.of("Line 1", "Line 2", "Line 3");
        List<String> yours = List.of("Line 1", "Line 2 [MINE]", "Line 3");
        List<String> theirs = List.of("Line 1", "Line 2 [THEIRS]", "Line 3");

        Merge3.MergeResult res = Merge3.merge(base, yours, theirs);
        assertTrue(res.isConflicted());

        List<String> expected = List.of(
            "Line 1",
            "<<<<<<< Yours",
            "Line 2 [MINE]",
            "=======",
            "Line 2 [THEIRS]",
            ">>>>>>> Theirs",
            "Line 3"
        );
        assertEquals(expected, res.getMergedLines());
    }
}
