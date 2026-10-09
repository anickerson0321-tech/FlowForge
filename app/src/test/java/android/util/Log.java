package android.util;

/**
 * TEST-ONLY replacement for the framework's android.util.Log, whose methods are native and
 * unavailable on a desktop JVM. It shadows the real class on the unit-test classpath only, so that
 * pure-Java framework classes (e.g. AccessibilityNodeInfo) can be exercised. It is never packaged
 * into the APK. Logging has no effect on the behavior under test.
 */
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6, ASSERT = 7;
    private Log() {}
    public static boolean isLoggable(String tag, int level) { return false; }
    public static int v(String t, String m) { return 0; }
    public static int v(String t, String m, Throwable e) { return 0; }
    public static int d(String t, String m) { return 0; }
    public static int d(String t, String m, Throwable e) { return 0; }
    public static int i(String t, String m) { return 0; }
    public static int i(String t, String m, Throwable e) { return 0; }
    public static int w(String t, String m) { return 0; }
    public static int w(String t, String m, Throwable e) { return 0; }
    public static int w(String t, Throwable e) { return 0; }
    public static int e(String t, String m) { return 0; }
    public static int e(String t, String m, Throwable e) { return 0; }
    public static int wtf(String t, String m) { return 0; }
    public static int wtf(String t, String m, Throwable e) { return 0; }
    public static String getStackTraceString(Throwable t) { return String.valueOf(t); }
    public static int println(int p, String t, String m) { return 0; }
}
