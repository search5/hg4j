package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.bundle.Bundle2;
import io.github.search5.hg4j.errors.HgProtocolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads the bundle2 stream a server answers an {@code unbundle} with: the parts a push gets back are {@code reply:changegroup}
 * (parameter {@code return}), {@code output} (what the server printed while it worked) and, when the server refused,
 * {@code error:abort}, {@code error:pushkey}, {@code error:pushraced} or {@code error:unsupportedcontent}.
 */
public final class Bundle2PushReply {

    private Bundle2PushReply() {
    }

    /**
     * @return the server's output followed by the changegroup return code (as real hg prints it)
     * @throws HgProtocolException if the answer carries an error part
     */
    public static String summarize(String url, byte[] reply) throws IOException {
        StringBuilder output = new StringBuilder();
        String returnCode = null;
        for (Bundle2.Part part : Bundle2.read(reply)) {
            String type = part.type.toLowerCase(java.util.Locale.ROOT);
            switch (type) {
                case "output" -> output.append(new String(part.payload, StandardCharsets.UTF_8));
                case "reply:changegroup" -> returnCode = part.param("return");
                case "error:abort", "error:pushkey", "error:unsupportedcontent", "error:pushraced" -> throw new HgProtocolException(
                        url, describe(type, part));
                default -> {
                    if (part.mandatory) {
                        throw new HgProtocolException(url, "unsupported mandatory part in the server's answer: " + part.type);
                    }
                }
            }
        }
        return output + (returnCode == null ? "" : returnCode + "\n");
    }

    private static String describe(String type, Bundle2.Part part) {
        String message = part.param("message");
        String hint = part.param("hint");
        String text = switch (type) {
            case "error:pushraced" -> "push failed: " + message;
            case "error:unsupportedcontent" -> "missing support for " + (part.param("parttype") == null ? "unknown content" : part.param("parttype"));
            case "error:pushkey" -> "pushkey failed: " + message;
            default -> message;
        };
        return "remote: abort: " + text + (hint == null ? "" : "\n(" + hint + ")");
    }
}
