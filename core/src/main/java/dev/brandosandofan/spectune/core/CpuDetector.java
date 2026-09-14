package dev.brandosandofan.spectune.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort CPU topology detection.
 *
 * <p>Detection is layered. Linux and macOS expose the hybrid split directly and are read exactly.
 * Windows gives core counts but not the performance/efficiency split without native calls, so the
 * split is derived: with SMT present the split is exact arithmetic ({@code P = logical - physical},
 * because only P-cores are hyper-threaded on every Intel hybrid part to date); without SMT it falls
 * back to a documented ladder. Anything inferred can be overridden in {@code spectune.properties}.
 *
 * <p>Every parsing step is a pure static method so it can be unit-tested without the host CPU.
 */
public final class CpuDetector {

    private static final Pattern MODEL_NAME = Pattern.compile("^model name\\s*:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern PROCESSOR_ID = Pattern.compile("^processor\\s*:\\s*(\\d+)$", Pattern.MULTILINE);
    private static final Pattern CORE_ID = Pattern.compile("^core id\\s*:\\s*(\\d+)$", Pattern.MULTILINE);
    private static final Pattern PHYSICAL_ID = Pattern.compile("^physical id\\s*:\\s*(\\d+)$", Pattern.MULTILINE);

    private CpuDetector() {}

    public static CpuTopology detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("linux")) {
                Optional<CpuTopology> sysfs = detectLinuxSysfs();
                if (sysfs.isPresent()) return sysfs.get();
                return detectLinuxProc().orElseGet(CpuTopology::fallback);
            }
            if (os.contains("win")) {
                return detectWindows().orElseGet(CpuTopology::fallback);
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return detectMac().orElseGet(CpuTopology::fallback);
            }
        } catch (RuntimeException e) {
            // Detection is advisory; never let it break game start-up.
        }
        return CpuTopology.fallback();
    }

    // ---------------------------------------------------------------- Linux

    static Optional<CpuTopology> detectLinuxSysfs() {
        String coreList = readFile(Path.of("/sys/devices/cpu_core/cpus"));
        String atomList = readFile(Path.of("/sys/devices/cpu_atom/cpus"));
        if (coreList == null || atomList == null) return Optional.empty();

        int pThreads = countCpuList(coreList);
        int eThreads = countCpuList(atomList);
        if (pThreads <= 0 || eThreads <= 0) return Optional.empty();

        String cpuinfo = readFile(Path.of("/proc/cpuinfo"));
        String brand = cpuinfo == null ? "Unknown CPU" : parseBrand(cpuinfo);
        int logical = pThreads + eThreads;
        int physicalFromProc = cpuinfo == null ? 0 : parsePhysicalCores(cpuinfo);
        // Atom (E) cores are never SMT, so every P-thread beyond the P-core count is a sibling.
        int pCores = physicalFromProc > 0 ? physicalFromProc - eThreads : pThreads;
        if (pCores < 1) pCores = pThreads;
        boolean smt = pThreads > pCores;
        return Optional.of(new CpuTopology(brand, logical, pCores + eThreads, pCores, eThreads, smt,
                CpuTopology.Source.SYSFS));
    }

    static Optional<CpuTopology> detectLinuxProc() {
        String cpuinfo = readFile(Path.of("/proc/cpuinfo"));
        if (cpuinfo == null) return Optional.empty();
        int logical = parseLogicalCores(cpuinfo);
        int physical = parsePhysicalCores(cpuinfo);
        if (logical < 1) return Optional.empty();
        if (physical < 1) physical = logical;
        String brand = parseBrand(cpuinfo);
        return Optional.of(infer(brand, logical, physical, CpuTopology.Source.PROC_CPUINFO));
    }

    /** Parses {@code "0-7,20-23"} style CPU lists into a thread count. */
    static int countCpuList(String list) {
        if (list == null) return 0;
        Set<Integer> cpus = new HashSet<>();
        for (String part : list.trim().split(",")) {
            if (part.isBlank()) continue;
            int dash = part.indexOf('-');
            try {
                if (dash < 0) {
                    cpus.add(Integer.parseInt(part.trim()));
                } else {
                    int from = Integer.parseInt(part.substring(0, dash).trim());
                    int to = Integer.parseInt(part.substring(dash + 1).trim());
                    for (int i = from; i <= to; i++) cpus.add(i);
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed ranges rather than failing the whole probe.
            }
        }
        return cpus.size();
    }

    static String parseBrand(String cpuinfo) {
        Matcher m = MODEL_NAME.matcher(cpuinfo);
        return m.find() ? m.group(1).trim() : "Unknown CPU";
    }

    static int parseLogicalCores(String cpuinfo) {
        int n = 0;
        Matcher m = PROCESSOR_ID.matcher(cpuinfo);
        while (m.find()) n++;
        return n;
    }

    /** Counts distinct {@code (physical id, core id)} pairs; falls back to the logical count. */
    static int parsePhysicalCores(String cpuinfo) {
        List<String> cores = new ArrayList<>();
        Matcher packages = PHYSICAL_ID.matcher(cpuinfo);
        Matcher ids = CORE_ID.matcher(cpuinfo);
        while (ids.find()) {
            String pkg = packages.find() ? packages.group(1) : "0";
            cores.add(pkg + ':' + ids.group(1));
        }
        return cores.isEmpty() ? 0 : new HashSet<>(cores).size();
    }

    // -------------------------------------------------------------- Windows

    static Optional<CpuTopology> detectWindows() {
        String csv = run(3, "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-CimInstance Win32_Processor | Select-Object -First 1 "
                        + "Name,NumberOfCores,NumberOfLogicalProcessors | ConvertTo-Csv -NoTypeInformation");
        Optional<CpuTopology> parsed = parseWindowsCsv(csv);
        if (parsed.isPresent()) return parsed;
        // wmic is deprecated but still present on plenty of installs.
        return parseWindowsCsv(run(3, "wmic", "cpu", "get",
                "Name,NumberOfCores,NumberOfLogicalProcessors", "/format:csv"));
    }

    /**
     * Parses the CSV emitted by either {@code ConvertTo-Csv} or {@code wmic /format:csv}. Column
     * order differs between the two, so columns are located by header name.
     */
    static Optional<CpuTopology> parseWindowsCsv(String csv) {
        if (csv == null || csv.isBlank()) return Optional.empty();
        String[] lines = csv.strip().split("\\R");
        int headerIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase(Locale.ROOT).contains("numberoflogicalprocessors")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0 || headerIdx + 1 >= lines.length) return Optional.empty();

        List<String> header = splitCsv(lines[headerIdx]);
        int nameCol = indexOfHeader(header, "name");
        int coreCol = indexOfHeader(header, "numberofcores");
        int logicalCol = indexOfHeader(header, "numberoflogicalprocessors");
        if (coreCol < 0 || logicalCol < 0) return Optional.empty();

        for (int i = headerIdx + 1; i < lines.length; i++) {
            List<String> row = splitCsv(lines[i]);
            if (row.size() <= Math.max(coreCol, logicalCol)) continue;
            int physical = parseInt(row.get(coreCol));
            int logical = parseInt(row.get(logicalCol));
            if (physical < 1 || logical < 1) continue;
            String brand = nameCol >= 0 && nameCol < row.size() ? row.get(nameCol) : "Unknown CPU";
            return Optional.of(infer(brand, logical, physical, CpuTopology.Source.WMI));
        }
        return Optional.empty();
    }

    private static int indexOfHeader(List<String> header, String want) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).strip().replace("\"", "").equalsIgnoreCase(want)) return i;
        }
        return -1;
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().strip());
        return out;
    }

    // ---------------------------------------------------------------- macOS

    static Optional<CpuTopology> detectMac() {
        String brand = run(3, "sysctl", "-n", "machdep.cpu.brand_string");
        int logical = parseInt(run(3, "sysctl", "-n", "hw.logicalcpu"));
        int physical = parseInt(run(3, "sysctl", "-n", "hw.physicalcpu"));
        if (logical < 1) return Optional.empty();
        if (physical < 1) physical = logical;
        int perf = parseInt(run(3, "sysctl", "-n", "hw.perflevel0.physicalcpu"));
        int eff = parseInt(run(3, "sysctl", "-n", "hw.perflevel1.physicalcpu"));
        if (perf > 0 && eff > 0) {
            return Optional.of(new CpuTopology(nullToUnknown(brand), logical, perf + eff, perf, eff,
                    logical > physical, CpuTopology.Source.SYSCTL));
        }
        return Optional.of(infer(nullToUnknown(brand), logical, physical, CpuTopology.Source.SYSCTL));
    }

    // ------------------------------------------------------------ Inference

    /**
     * Derives the P/E split from core counts alone.
     *
     * <p>When SMT is present the split is exact: on Intel hybrid parts only P-cores are
     * hyper-threaded, so {@code logical = 2P + E} and {@code physical = P + E} give
     * {@code P = logical - physical}.
     *
     * <p>When SMT is absent (Arrow Lake and later) the counts carry no split information, so a
     * conservative ladder is applied and the result is flagged as inferred. Override it in
     * {@code config/spectune.properties} if your part differs.
     */
    static CpuTopology infer(String brand, int logical, int physical, CpuTopology.Source source) {
        boolean smt = logical > physical;
        if (smt) {
            int p = logical - physical;
            int e = physical - p;
            if (p >= 1 && e >= 1) {
                return new CpuTopology(brand, logical, physical, p, e, true, source);
            }
            return new CpuTopology(brand, logical, physical, physical, 0, true, source);
        }
        if (looksHybrid(brand)) {
            int p = ladderPerformanceCores(physical);
            if (p > 0 && p < physical) {
                return new CpuTopology(brand, logical, physical, p, physical - p, false, source);
            }
        }
        return new CpuTopology(brand, logical, physical, physical, 0, false, source);
    }

    /** Families known to ship performance + efficiency cores. */
    static boolean looksHybrid(String brand) {
        if (brand == null) return false;
        String b = brand.toLowerCase(Locale.ROOT);
        if (b.contains("core ultra") || b.contains("core(tm) ultra")) return true;
        if (b.contains("apple m")) return true;
        Matcher gen = Pattern.compile("i[3579]-(1[2-4])\\d{3}").matcher(b);
        return gen.find(); // 12th-14th gen Alder/Raptor Lake
    }

    /**
     * Ladder for non-SMT hybrid parts, matching the shipped configurations of Intel's mobile
     * line-up (24C → 8P+16E, 20C → 8P+12E, 16C → 6P+10E, and so on downward).
     */
    static int ladderPerformanceCores(int physical) {
        if (physical >= 20) return 8;
        if (physical >= 14) return 6;
        if (physical >= 10) return 4;
        if (physical >= 6) return 2;
        return 0;
    }

    // ---------------------------------------------------------------- Utils

    private static String nullToUnknown(String s) {
        return s == null || s.isBlank() ? "Unknown CPU" : s.strip();
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        String t = s.strip().replace("\"", "");
        try {
            return t.isEmpty() ? -1 : Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Runs a probe command with a hard timeout; returns {@code null} on any failure. */
    private static String run(int timeoutSeconds, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return null;
            return process.exitValue() == 0 ? out : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
