package com.jcraft.jsch;

/**
 * Test-only helper living in the {@code com.jcraft.jsch} package (JSch's own package) so it can
 * reach {@link Session}'s package-private constructor. Used exclusively by
 * {@code com.github.search5.hg4j.transport.JschSshSessionCoverageTest} to force
 * {@link Session#disconnect()} to throw, exercising the defensive
 * {@code catch (Exception ignored)} block in {@code JschSshSession.close()}.
 */
public class ThrowingSession extends Session {

    public ThrowingSession() throws JSchException {
        super(new JSch(), "coverage-user", "coverage-host", 22);
    }

    @Override
    public void disconnect() {
        throw new RuntimeException("boom - simulated session.disconnect() failure");
    }
}
