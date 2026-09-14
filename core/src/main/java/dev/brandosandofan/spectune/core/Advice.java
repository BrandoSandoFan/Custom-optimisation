package dev.brandosandofan.spectune.core;

/** One finding about the current JVM or machine setup, with the fix where there is one. */
public record Advice(Severity severity, String title, String detail, String fix) {

    public enum Severity {
        /** Worth knowing, nothing is wrong. */
        INFO,
        /** Costing performance, fix when convenient. */
        WARN,
        /** Costing a lot of performance, or the game is not running on the hardware you think. */
        CRITICAL
    }

    public static Advice info(String title, String detail) {
        return new Advice(Severity.INFO, title, detail, null);
    }

    public static Advice warn(String title, String detail, String fix) {
        return new Advice(Severity.WARN, title, detail, fix);
    }

    public static Advice critical(String title, String detail, String fix) {
        return new Advice(Severity.CRITICAL, title, detail, fix);
    }

    public String format() {
        String line = "[" + severity + "] " + title + " - " + detail;
        return fix == null ? line : line + " Fix: " + fix;
    }
}
