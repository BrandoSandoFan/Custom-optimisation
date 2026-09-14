package dev.brandosandofan.spectune.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the OpenGL driver says it is. On a laptop this is the single most valuable thing to check:
 * if the renderer string names an integrated GPU, the game is not running on the discrete card and
 * no amount of tuning will matter until that is fixed.
 */
public record GpuInfo(String vendor, String renderer, String version, Tier tier, boolean integrated) {

    public enum Tier {
        /** RTX 40/50 series, high-end RDNA3/4, and anything else that can push a big render distance. */
        HIGH,
        MID,
        LOW,
        /** iGPU, software renderer, or unrecognised. */
        INTEGRATED,
        UNKNOWN
    }

    private static final Pattern NVIDIA_RTX = Pattern.compile("rtx\\s*(\\d{4})");
    private static final Pattern NVIDIA_GTX = Pattern.compile("gtx\\s*(\\d{3,4})");
    private static final Pattern RADEON_RX = Pattern.compile("rx\\s*(\\d{4})");

    public static GpuInfo of(String vendor, String renderer, String version) {
        String r = normalise(renderer);
        boolean integrated = isIntegrated(r, vendor);
        return new GpuInfo(
                vendor == null ? "unknown" : vendor,
                renderer == null ? "unknown" : renderer,
                version == null ? "unknown" : version,
                integrated ? Tier.INTEGRATED : classify(r),
                integrated);
    }

    /** Lower-cases and drops the {@code (R)} / {@code (TM)} noise vendors put in renderer strings. */
    static String normalise(String renderer) {
        if (renderer == null) return "";
        return renderer.toLowerCase(Locale.ROOT)
                .replace("(r)", " ")
                .replace("(tm)", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static boolean isIntegrated(String renderer, String vendor) {
        String v = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
        if (renderer.contains("llvmpipe") || renderer.contains("softpipe") || renderer.contains("swiftshader")) {
            return true;
        }
        if (renderer.contains("intel") || v.contains("intel")) {
            // Intel's discrete Arc A/B-series are the exception to "Intel means iGPU"; the Arc-branded
            // integrated graphics in Meteor Lake and later carry no model number.
            return !renderer.matches(".*\\barc\\b.*\\b[ab]\\d{3}\\b.*");
        }
        return renderer.contains("radeon graphics") && !renderer.contains("rx");
    }

    static Tier classify(String renderer) {
        Matcher rtx = NVIDIA_RTX.matcher(renderer);
        if (rtx.find()) {
            int model = Integer.parseInt(rtx.group(1));
            int series = model / 1000;
            int rank = model % 1000;
            if (series >= 4) return rank >= 70 ? Tier.HIGH : Tier.MID;
            return rank >= 80 ? Tier.HIGH : Tier.MID;
        }
        Matcher gtx = NVIDIA_GTX.matcher(renderer);
        if (gtx.find()) return Tier.LOW;
        Matcher rx = RADEON_RX.matcher(renderer);
        if (rx.find()) {
            int model = Integer.parseInt(rx.group(1));
            return model % 1000 >= 700 ? Tier.HIGH : Tier.MID;
        }
        if (renderer.contains("apple m")) return Tier.MID;
        return Tier.UNKNOWN;
    }

    public String describe() {
        return renderer + " (" + vendor + ", GL " + version + ") -> tier " + tier;
    }
}
