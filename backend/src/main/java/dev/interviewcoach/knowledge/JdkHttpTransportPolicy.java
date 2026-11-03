package dev.interviewcoach.knowledge;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

/** JDK会静态缓存HTTP重试参数；必须在JVM启动时限制，不能用运行中setProperty冒充已生效。 */
public final class JdkHttpTransportPolicy {
    private static final Map<String, String> REQUIRED = Map.of(
            "jdk.httpclient.redirects.retrylimit", "1",
            "jdk.httpclient.disableRetryConnect", "true",
            "jdk.httpclient.enableAllMethodRetry", "false");
    private static final boolean STARTUP_SAFE = startupSafe();

    private JdkHttpTransportPolicy() { }

    public static List<String> jvmArguments() {
        return List.of("-Djdk.httpclient.redirects.retrylimit=1", "-Djdk.httpclient.disableRetryConnect=true",
                "-Djdk.httpclient.enableAllMethodRetry=false");
    }

    public static boolean enforced() {
        if (!STARTUP_SAFE) return false;
        for (var entry : REQUIRED.entrySet()) {
            if (!entry.getValue().equals(System.getProperty(entry.getKey()))) return false;
        }
        return true;
    }

    private static boolean startupSafe() {
        if (Runtime.version().feature() != 21) return false;
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (var entry : REQUIRED.entrySet()) {
            String prefix = "-D" + entry.getKey() + "=";
            String last = null;
            for (String argument : arguments) {
                if (argument.startsWith(prefix)) last = argument.substring(prefix.length());
            }
            // 相同属性的最后一个启动值才有效；同时检查当前值，拒绝后续篡改。
            if (!entry.getValue().equals(last)) return false;
        }
        return true;
    }
}
