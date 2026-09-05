package io.github.search5.hg4j.submodule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for {@link HgSubrepoEntry}, targeting full instruction/branch coverage:
 * constructor null-defaulting behavior, the null-path guard, equals()/hashCode()/toString()
 * contracts, and getter accessors.
 */
public class HgSubrepoEntryTest {

    @Test
    public void testConstructorNullPathThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new HgSubrepoEntry(null, "http://example.com", "abc123", false));
        assertEquals("Subrepo path cannot be null", ex.getMessage());
    }

    @Test
    public void testConstructorNullSourceUrlDefaultsToEmpty() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/foo", null, "abc123", false);
        assertEquals("", entry.getSourceUrl());
    }

    @Test
    public void testConstructorNullRevisionDefaultsToEmpty() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/foo", "http://example.com", null, false);
        assertEquals("", entry.getRevision());
    }

    @Test
    public void testConstructorNonNullValuesRetained() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", true);
        assertEquals("libs/foo", entry.getPath());
        assertEquals("http://example.com", entry.getSourceUrl());
        assertEquals("abc123", entry.getRevision());
        assertTrue(entry.isGit());
    }

    @Test
    public void testGettersForNonGitEntry() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/bar", "https://hg.example.com/bar", "deadbeef", false);
        assertEquals("libs/bar", entry.getPath());
        assertEquals("https://hg.example.com/bar", entry.getSourceUrl());
        assertEquals("deadbeef", entry.getRevision());
        assertFalse(entry.isGit());
    }

    @Test
    public void testEqualsSameInstance() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", false);
        assertEquals(entry, entry);
    }

    @Test
    public void testEqualsNull() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", false);
        // Call entry.equals(null) directly: assertNotEquals(null, entry) would route through
        // Objects.equals(null, entry), which short-circuits on the null and never invokes
        // HgSubrepoEntry.equals() at all.
        assertFalse(entry.equals(null));
    }

    @Test
    public void testEqualsDifferentClass() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", false);
        // Call entry.equals(...) directly: assertNotEquals("not an entry", entry) would invoke
        // String.equals(entry) instead of HgSubrepoEntry.equals(), missing the getClass() branch.
        assertFalse(entry.equals("not an entry"));
    }

    @Test
    public void testEqualsDifferentIsGit() {
        HgSubrepoEntry a = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", false);
        HgSubrepoEntry b = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", true);
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsDifferentPath() {
        HgSubrepoEntry a = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", false);
        HgSubrepoEntry b = new HgSubrepoEntry("libs/other", "http://example.com", "abc123", false);
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsDifferentSourceUrl() {
        HgSubrepoEntry a = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", false);
        HgSubrepoEntry b = new HgSubrepoEntry("libs/foo", "http://other.example.com", "abc123", false);
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsDifferentRevision() {
        HgSubrepoEntry a = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", false);
        HgSubrepoEntry b = new HgSubrepoEntry("libs/foo", "http://example.com", "def456", false);
        assertNotEquals(a, b);
    }

    @Test
    public void testEqualsFullyEqualDistinctInstances() {
        HgSubrepoEntry a = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", true);
        HgSubrepoEntry b = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testHashCodeConsistentAcrossCalls() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", false);
        assertEquals(entry.hashCode(), entry.hashCode());
    }

    @Test
    public void testSvnConstructorSetsSvnTypeAndNotGit() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/svnlib", "https://svn.example.com/trunk", "5", false, true);
        assertTrue(entry.isSvn());
        assertFalse(entry.isGit());
        assertEquals(HgSubrepoEntry.Type.SVN, entry.getType());
    }

    @Test
    public void testFiveArgConstructorGitFlagWhenSvnFalse() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/gitlib", "https://example.com/repo.git", "abc", true, false);
        assertTrue(entry.isGit());
        assertFalse(entry.isSvn());
        assertEquals(HgSubrepoEntry.Type.GIT, entry.getType());
    }

    @Test
    public void testFiveArgConstructorSvnWinsWhenBothFlagsSet() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/x", "url", "1", true, true);
        assertTrue(entry.isSvn());
        assertFalse(entry.isGit());
    }

    @Test
    public void testTypeConstructorHgDefaultsWhenNull() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/x", "url", "1", (HgSubrepoEntry.Type) null);
        assertEquals(HgSubrepoEntry.Type.HG, entry.getType());
        assertFalse(entry.isGit());
        assertFalse(entry.isSvn());
    }

    @Test
    public void testEqualsDistinguishesSvnFromGitAndHg() {
        HgSubrepoEntry hg = new HgSubrepoEntry("p", "u", "r", false, false);
        HgSubrepoEntry git = new HgSubrepoEntry("p", "u", "r", true, false);
        HgSubrepoEntry svn = new HgSubrepoEntry("p", "u", "r", false, true);
        assertNotEquals(hg, git);
        assertNotEquals(hg, svn);
        assertNotEquals(git, svn);
    }

    @Test
    public void testToStringContainsSvnFieldValue() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/svnlib", "https://svn.example.com/trunk", "5", false, true);
        String s = entry.toString();
        assertTrue(s.contains("isSvn=true"));
        assertTrue(s.contains("isGit=false"));
    }

    @Test
    public void testToStringContainsFieldValues() {
        HgSubrepoEntry entry = new HgSubrepoEntry("libs/foo", "http://example.com", "abc123", true);
        String s = entry.toString();
        assertTrue(s.contains("libs/foo"));
        assertTrue(s.contains("http://example.com"));
        assertTrue(s.contains("abc123"));
        assertTrue(s.contains("isGit=true"));
    }
}
