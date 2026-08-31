# llm-wiki schema (hg4j-specific)

**IMPORTANT: every rule in this document — especially section 8 and the new section 9 below —
is MANDATORY, not advisory. Any agent (Claude, Gemini, Codex, or otherwise) working in this
repository MUST follow them exactly. Producing text that merely sounds like compliance
without the concrete evidence these rules demand does NOT satisfy them.**

This document defines the authoring rules for the `llm-wiki/` directory itself. It adapts
Karpathy's "LLM Wiki" pattern (an accumulating compiled wiki, as opposed to RAG) to the hg4j
library repository.

## Why this structure

- hg4j is not a Jira-ticket-driven project but a **single open-source library**, so instead of
  megabird's `llm-wiki/<ticket-id>/` structure, this wiki is split along **concept / module /
  decision** axes.
- The goal: "the next agent (or human) looking at this code should be able to orient itself
  from this wiki alone, without re-reading the entire original source."

## Directory structure

```
llm-wiki/
├── AGENTS.md      # this file — the wiki's own schema/authoring rules
├── index.md       # full page catalog (mandatory entry point, always read first)
├── log.md         # chronological work history
├── implementation-plan.md  # self-contained master plan handed off to another agent (e.g. Gemini)
├── concepts/      # Mercurial domain concepts (revlog, dirstate, bundle2, revset, ...)
├── modules/       # structural notes per com.github.search5.hg4j.* package
├── decisions/     # architecture decision records (ADR-style) — "why this was done"
└── sources/       # summarized snapshots of raw research (git log, issues, etc.)
```

## `implementation-plan.md` vs `decisions/*.md`

- `decisions/*.md`: holds the **background/rationale** for each decision (why it was done this
  way, what the alternatives were). This is a knowledge compilation for Claude (the agent that
  maintains this wiki).
- `implementation-plan.md`: consolidates the "not yet executed" items scattered across multiple
  `decisions/*.md` files into **one concrete execution order, checklist, and set of commands**.
  It must be written self-containedly enough **to hand off as-is to an external agent with no
  conversation context (e.g. Gemini)** — minimize background explanation (replace it with
  reference links), but spell out "what, in what order, and how it's verified" without
  omission.
- Once execution is finished, do **not** keep updating `implementation-plan.md` — update the
  original `decisions/*.md` and `modules/*.md` instead. The execution plan is a one-time
  snapshot; decisions/modules are the continuously maintained source of truth.

## Authoring rules

1. **Always read `index.md` first.** Do not traverse the whole wiki.
2. Adding a new page requires adding a one-line catalog entry to `index.md`.
3. Cross-page references use `[[page-name]]` wikilink style or relative markdown links.
4. When code changes make a page's content wrong (a contradiction), fix it immediately and
   leave a one-line note in `log.md` about what changed and why. Do not leave stale information
   in place.
5. **`decisions/`** pages focus on "why this decision was made," not "what was done" (including
   why alternatives, if any, were rejected).
6. Every page starts with minimal YAML frontmatter:
   ```yaml
   ---
   updated: YYYY-MM-DD
   status: current | stale | superseded-by:[[other-page]]
   ---
   ```
7. **Search/audit rigor rule**: when searching the codebase (grep, etc.) or performing an audit,
   do not arbitrarily drop or add a trailing dot (`.`) or a specific extension format on file
   names. For example, to find a config/journal file with no extension (like `journal`), search
   for the bare string without a dot. Do not jump to "this feature is not implemented at all"
   based solely on a simple tool lookup — cross-check the actual coupling points and logic flow
   between related components to guarantee the audit is trustworthy.
8. **No speculation, no invented hypotheses (empirical verification is mandatory)**: when
   analyzing, planning, or implementing code, never dress up speculative information or
   imagined byte/offset specs as if they were fact.
   - Binary format details, UUID placement, padding, and similar specifics must be verified
     byte-for-byte against the actual official internals documentation or actual C/Python
     source (hexdump-level empirical verification) with zero discrepancy before being treated
     as settled.
   - Do not jump to conclusions from a single source diff — use `git status` etc. to fully
     analyze every organic change across the whole scope of the work.
   - Never mix unconfirmed speculation into an answer as if it were fact, and never paper over
     gaps with deceptive tests written just to look green; when evidence is insufficient, state
     the limitation explicitly and stick to facts only.
   - **No firing off a build (Gradle/tests) before static completeness is verified**: do not
     rely on a sloppy build's failure output to patch compile errors reactively. Before editing,
     verify 100% in the code itself — inheritance structure, import conditions, brace pairing —
     for the target file.
   - **Full 1:N dependency check**: when changing a class or utility, don't stop at the file
     itself — use `grep_search` to enumerate every caller class and test class that uses it, and
     fully analyze the impact before making the change.
   - **Recovery/teardown design takes top priority**: when designing transactions, write
     commands, or lock-related operations, build in — as the highest priority — the guarantee
     that any temporary file/state on disk or in memory is fully restored and cleaned up if the
     method exits early via an exception or an early `return`.
   - **No fake mocked tests**: it is strictly forbidden to fake a real SCM spec check or wire
     protocol behavior with a fake mock HTTP server or blind dummy-data responses just to make
     the test result look green because the real check is inconvenient. Tests must verify
     correctness at the level of the actual revision graph or actual binary byte stream.
   - **No catch-and-swallow**: on a communication or I/O exception, it is forbidden to
     irresponsibly patch over it by quietly swallowing it in a `try-catch` (or logging and
     ignoring it) to fake a successful outcome. Exceptions must propagate (be rethrown)
     accurately to the upper business layer.
   - **Mandatory full self-critique pass before declaring completion**: before answering that an
     implementation is complete, you must write out, inside your thinking, a self-checklist
     covering every Java file you changed and the impact on storage/disk, and interrogate and
     critique it.
   - **Written-out verification ("no brainless coding")**: before firing off a tool to run
     changed code, you must explicitly declare, inside your thinking, in complete sentences, the
     impact analysis the change causes (list of dependent classes and call paths). You may not
     lean on running a tool while the analysis is still "roughly" done in your head.
   - **Eliminate the deceptive-green-test syndrome**: do not settle for a bare "tests passed"
     message — you must prove, inside your thinking, exactly which clause of the actual
     Mercurial spec document the final implemented code matches, one-to-one.
   - **Record deliberate leaks and trade-offs**: if you decided to ignore or work around a
     specific exception-handling case or an indirect impact because of business complexity, do
     not hide it — honestly state, both in the wiki and in your thinking, the scope of what you
     worked around and the risk, so the next session is informed.

9. **CRITICAL — completion claims require evidence, not narrative. (Added after an external
   agent, when asked why it kept failing to follow guidance, produced a long dramatic
   self-reflection essay about its own "nature" instead of changing anything — that response is
   itself the failure mode this rule exists to shut down. A model cannot fix its behavior by
   narrating about its behavior; behavior is only fixed by removing its ability to self-certify
   success without evidence.)**
   - **Never declare a task "done" from prose alone.** Every completion claim must be backed by
     attached, checkable evidence: a diff, a command's actual output, or an actual response
     captured from a running process. No evidence, no "done" — no matter how confident or
     detailed the prose is.
   - **Do not answer "why did this fail" with a self-reflection essay.** When asked why
     something broke or why a rule wasn't followed, the only acceptable answer format is: (a)
     the specific wrong assumption or skipped step, (b) the diff that fixes it, (c) the
     command/output that proves the fix. A narrative about "carelessness," "haste," or the
     model's "nature" changes nothing and must not be produced. If you don't know, say "unknown,
     here is what I checked and did not find" — nothing else.
   - **A green test suite is not sufficient on its own.** A mocked test proves the mock was
     satisfied, not that real behavior is correct — this is exactly what rule 8's "no fake
     mocked tests" already demands; rule 9 makes explicit that no amount of apologetic prose may
     substitute for that same empirical check.
   - **If a rule genuinely cannot be honestly satisfied, state the concrete blocker** — the
     specific missing evidence or the specific obstacle — never an apology or a promise to "try
     harder" in its place.

## The 3 core tasks (borrowed from the Karpathy pattern)

- **Ingest**: read new commits/issues/design discussions and update the relevant
  concepts/modules/decisions pages. If it's a wholly new concept, create a new page.
- **Query**: for "how does X work?"-style questions, search the wiki first; if it's not there,
  read the code, answer, and save that answer as a new page (or an addition to an existing one).
- **Lint**: periodically check that `index.md`'s links still match real files, and that
  code paths/class names haven't drifted out of date due to refactoring (e.g. if a class name
  like `HgRepository` changes, update immediately).

## What this wiki does not cover

- Global rules already in CLAUDE.md (language, Python work rules, DB logging conventions, etc.)
  are not duplicated here.
- Facts already trackable via git (commit history, blame) are not copied verbatim — only
  interpretation/summary is kept here.
