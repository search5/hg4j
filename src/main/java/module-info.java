module org.hg4j {
    exports org.hg4j.core;
    exports org.hg4j.api;
    requires java.logging;
    requires org.apache.commons.compress;
    requires com.github.luben.zstd_jni;
    requires com.jcraft.jsch;
}
