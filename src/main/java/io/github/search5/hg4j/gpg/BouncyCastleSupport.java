package io.github.search5.hg4j.gpg;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

/** Registers the Bouncy Castle JCA provider once. */
final class BouncyCastleSupport {

    private BouncyCastleSupport() {
    }

    static void ensureRegistered() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
}
