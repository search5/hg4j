package io.github.search5.hg4j.errors;

/**
 * Thrown when the repository's heads changed underneath a push between when the pushing client
 * computed it and when the server actually acquired the store lock to apply it — i.e. the losing
 * side of a genuine concurrent-push race.
 *
 * @apiNote Thrown by {@code io.github.search5.hg4j.transport.HgLocalClient}'s server-side
 *     push/unbundle apply path. It is a distinct subtype (rather than a generic {@link
 *     HgValidationException}) so callers that want to specifically detect and retry a raced push
 *     can catch it by type; it remains fully compatible with any existing
 *     {@code catch (HgValidationException ...)} or generic {@code catch (IOException ...)}
 *     handler since it extends {@link HgValidationException}. Unlike real hg's client, hg4j does
 *     not retry automatically — the caller (e.g. a {@code PushCommand} user) must re-fetch and
 *     retry.
 * @implNote Mirrors the scenario real hg's own {@code error.PushRaced} (see
 *     {@code mercurial/error.py}) guards against via {@code
 *     mercurial/bundle2_part_handlers.py}'s {@code check:heads}/{@code check:updated-heads} part
 *     handlers (bundle2 pushes) and {@code mercurial/exchange.py}'s {@code check_heads()}
 *     (legacy bundle1 pushes).
 */
public class HgPushRacedException extends HgValidationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given detail message.
     *
     * @param message a description of the head-change race that was detected
     */
    public HgPushRacedException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given detail message and underlying cause.
     *
     * @param message a description of the head-change race that was detected
     * @param cause the underlying exception that surfaced the race, if any
     */
    public HgPushRacedException(String message, Throwable cause) {
        super(message, cause);
    }
}
