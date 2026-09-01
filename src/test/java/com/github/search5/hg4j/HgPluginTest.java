package com.github.search5.hg4j;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HgPluginTest {

    @Test
    public void applyLogsLifecycleMessageContainingProjectName() {
        List<String> lifecycleMessages = new ArrayList<>();

        Logger logger = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    if ("lifecycle".equals(method.getName()) && args != null && args.length == 1) {
                        lifecycleMessages.add(String.valueOf(args[0]));
                    }
                    return null;
                });

        Project project = (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLogger":
                            return logger;
                        case "getName":
                            return "hg4j-test-project";
                        default:
                            return null;
                    }
                });

        new HgPlugin().apply(project);

        assertEquals(1, lifecycleMessages.size());
        assertTrue(lifecycleMessages.get(0).contains("hg4j-test-project"));
        assertTrue(lifecycleMessages.get(0).contains("hg4j"));
    }
}
