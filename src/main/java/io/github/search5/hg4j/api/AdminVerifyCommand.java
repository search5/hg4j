package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * {@code hg admin::verify} ({@code admin_commands.admin_verify}, {@code admin/verify.py}): runs named checks of the repository and reports
 * what they find. Mercurial 7.2 registers one check, {@code working-copy.dirstate} (alias {@code dirstate}): the dirstate against the
 * manifests of its parents. A check is named by its qualified name, an alias, a prefix of a qualified name ({@code working-copy} runs all
 * below it) or {@code full}. Nothing is locked: it only reads.
 */
public final class AdminVerifyCommand {

    /** One registered check: its qualified name, alias, and the options it accepts (none for the one that exists). */
    private record Check(String name, String alias, List<String> options) {
    }

    private static final List<Check> CHECKS = List.of(new Check("working-copy.dirstate", "dirstate", List.of()));

    private final HgRepository repository;
    private final List<String> names = new ArrayList<>();
    private final List<String> options = new ArrayList<>();
    private final List<String> output = new ArrayList<>();
    private final List<String> errorOutput = new ArrayList<>();

    /**
     * Creates an {@code hg admin::verify} command for the given repository. No check runs until it is executed.
     *
     * @param repository the repository to operate on; must not be {@code null}
     */
    public AdminVerifyCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** {@code -c/--check}: a check to add; with none, nothing runs ({@code get_checks} of an empty list). */
    public AdminVerifyCommand addCheck(String name) {
        names.add(name);
        return this;
    }

    /** {@code -o/--option}: {@code check:option=value}. */
    public AdminVerifyCommand addOption(String option) {
        options.add(option);
        return this;
    }

    /** What {@code ui.status} printed. */
    public List<String> getOutput() {
        return output;
    }

    /** What {@code ui.warn} printed: the findings, and the warning about an abandoned transaction. */
    public List<String> getWarnings() {
        return errorOutput;
    }

    /** {@code find_checks}: all checks a name stands for, by qualified name. */
    static Map<String, Check> findChecks(String name) throws HgValidationException {
        Map<String, Check> found = new TreeMap<>();
        if (name.equals("full")) {
            CHECKS.forEach(c -> found.put(c.name(), c));
            return found;
        }
        String resolved = name;
        for (Check c : CHECKS) {
            if (c.alias() != null && c.alias().equals(name)) {
                resolved = c.name();
            }
        }
        int dot = name.indexOf('.');
        if (dot > 0) { // an alias for the first level, as in `revlogs.changelog`
            for (Check c : CHECKS) {
                if (c.alias() != null && c.alias().equals(name.substring(0, dot))) {
                    resolved = c.name() + "." + name.substring(dot + 1);
                }
            }
        }
        for (Check c : CHECKS) {
            if (c.name().equals(resolved) || c.name().startsWith(resolved + ".")) {
                found.put(c.name(), c);
            }
        }
        if (found.isEmpty()) {
            throw new HgValidationException("unknown check " + resolved);
        }
        return found;
    }

    /** {@code pass_options}'s validation: {@code check:option=value}; the check has to take the option. */
    private void validateOptions(Map<String, Check> selected) throws HgValidationException {
        for (String option : options.stream().sorted().toList()) {
            String hint = "\n(hint: syntax is 'check:option=value', eg. revlogs.changelog:copies=yes)";
            String[] split = option.split(":", -1);
            if (split.length != 2 || split[1].isEmpty()) {
                throw new HgValidationException("invalid option '" + option + "'" + hint);
            }
            String[] assignment = split[1].split("=", -1);
            if (assignment.length != 2 || assignment[1].isEmpty()) {
                throw new HgValidationException("invalid option '" + option + "'" + hint);
            }
            for (String name : findChecks(split[0]).keySet()) {
                Check check = selected.get(name);
                if (check == null) {
                    throw new HgValidationException("specified option '" + name + "' for unselected check '" + assignment[0] + "'");
                }
                if (!check.options().contains(assignment[0])) {
                    throw new HgValidationException("check '" + name + "' has no option '" + assignment[0] + "'");
                }
            }
        }
    }

    /**
     * Runs the selected checks.
     *
     * @return the number of errors found by each check that found some, by qualified name
     */
    public Map<String, Integer> call() throws IOException {
        output.clear();
        errorOutput.clear();
        if (new File(repository.getStoreDir(), "journal").exists()) { // transaction.has_abandoned_transaction
            errorOutput.add("abandoned transaction found - run hg recover\n");
        }
        Map<String, Check> selected = new TreeMap<>();
        for (String name : names) {
            selected.putAll(findChecks(name));
        }
        if (selected.isEmpty()) {
            throw new HgValidationException("`checks` required"); // pass_options, whether or not options were given
        }
        validateOptions(selected);
        output.add("running " + selected.size() + " checks\n");
        Map<String, Integer> found = new TreeMap<>();
        for (Check check : selected.values()) {
            output.add("running " + check.name() + "\n");
            int errors = run(check);
            if (errors > 0) {
                errorOutput.add("found " + errors + " errors\n");
                found.put(check.name(), errors);
            }
        }
        return found;
    }

    private int run(Check check) throws IOException {
        if (check.name().equals("working-copy.dirstate")) {
            return checkDirstate();
        }
        throw new IllegalStateException(check.name());
    }

    /** {@code check_dirstate}: {@code dirstate.verify(m1, m2, parent1, narrow_matcher)} with its four messages. */
    private int checkDirstate() throws IOException {
        output.add("checking dirstate\n");
        Dirstate dirstate = repository.getDirstate();
        byte[] p1 = dirstate.getParent1();
        byte[] p2 = dirstate.getParent2();
        Map<String, String> m1 = NodeIdUtil.isAllZero(p1) ? Map.of() : repository.getManifestAtCommit(p1);
        Map<String, String> m2 = NodeIdUtil.isAllZero(p2) ? Map.of() : repository.getManifestAtCommit(p2);
        Predicate<String> narrow = null;
        if (new File(repository.getStoreDir(), "narrowspec").exists()) {
            io.github.search5.hg4j.treewalk.HgTreeFilter filter = io.github.search5.hg4j.treewalk.HgTreeFilter.loadForWorkingCopy(repository);
            narrow = filter::accept;
        }
        String short1 = NodeIdUtil.toHex(p1).substring(0, 12);
        int errors = 0;
        Map<String, Dirstate.Entry> entries = new TreeMap<>(dirstate.getEntries());
        for (Map.Entry<String, Dirstate.Entry> e : entries.entrySet()) {
            String f = e.getKey();
            char state = e.getValue().getState();
            boolean p1Tracked = state == 'n' || state == 'm' || state == 'r';
            boolean modified = state == 'm';
            boolean added = state == 'a';
            if (p1Tracked) {
                if (modified && !m1.containsKey(f) && !m2.containsKey(f)) {
                    errorOutput.add(f + " marked as modified, but not in either manifest\n");
                    errors++;
                } else if (!m1.containsKey(f)) {
                    errorOutput.add(f + " marked as tracked in p1 (" + short1 + ") but not in manifest1\n");
                    errors++;
                }
            }
            if (added && m1.containsKey(f)) {
                errorOutput.add(f + " marked as added, but also in manifest1\n");
                errors++;
            }
        }
        for (String f : new TreeMap<>(m1).keySet()) {
            if (narrow != null && !narrow.test(f)) {
                continue;
            }
            Dirstate.Entry entry = entries.get(f);
            boolean p1Tracked = entry != null && (entry.getState() == 'n' || entry.getState() == 'm' || entry.getState() == 'r');
            if (!p1Tracked) {
                errorOutput.add(f + " in manifest1, but not marked as tracked in p1 (" + short1 + ")\n");
                errors++;
            }
        }
        return errors;
    }
}
