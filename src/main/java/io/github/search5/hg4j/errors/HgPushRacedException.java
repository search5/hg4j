package io.github.search5.hg4j.errors;

/**
 * Thrown by the server-side push/unbundle apply path (backlog item 38) when the repository's
 * heads changed underneath a push between when the pushing client computed it and when the
 * server actually acquired the store lock to apply it -- i.e. a losing side of a genuine
 * concurrent-push race, exactly the scenario real hg's own {@code error.PushRaced} (see {@code
 * mercurial/error.py}) guards against via {@code mercurial/bundle2_part_handlers.py}'s {@code
 * check:heads}/{@code check:updated-heads} part handlers (bundle2 pushes) and {@code
 * mercurial/exchange.py}'s {@code check_heads()} (legacy bundle1 pushes).
 *
 * <p>A distinct subtype (rather than a generic {@link HgValidationException}) so callers that
 * want to specifically detect and retry a raced push -- exactly what real hg's own client does
 * not do automatically either, but what the message tells a human/script to do -- can catch it
 * by type; it remains fully compatible with any existing {@code catch (HgValidationException ...)}
 * or generic {@code catch (IOException ...)} handler since it extends {@link
 * HgValidationException}.
 */
public class HgPushRacedException extends HgValidationException {
    private static final long serialVersionUID = 1L;

    public HgPushRacedException(String message) {
        super(message);
    }

    public HgPushRacedException(String message, Throwable cause) {
        super(message, cause);
    }
}
