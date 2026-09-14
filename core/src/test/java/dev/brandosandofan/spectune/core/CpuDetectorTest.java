package dev.brandosandofan.spectune.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CpuDetectorTest {

    @Test
    void parsesCpuRangeLists() {
        assertEquals(20, CpuDetector.countCpuList("0-19"));
        assertEquals(12, CpuDetector.countCpuList("0-11"));
        assertEquals(6, CpuDetector.countCpuList("0-3,8,9"));
        assertEquals(0, CpuDetector.countCpuList(""));
        assertEquals(1, CpuDetector.countCpuList("7"));
        assertEquals(1, CpuDetector.countCpuList("7,junk"));
    }

    @Test
    void parsesProcCpuinfo() {
        String cpuinfo = """
                processor	: 0
                model name	: Intel(R) Core(TM) Ultra 9 185H
                physical id	: 0
                core id		: 0

                processor	: 1
                model name	: Intel(R) Core(TM) Ultra 9 185H
                physical id	: 0
                core id		: 0

                processor	: 2
                model name	: Intel(R) Core(TM) Ultra 9 185H
                physical id	: 0
                core id		: 1
                """;
        assertEquals("Intel(R) Core(TM) Ultra 9 185H", CpuDetector.parseBrand(cpuinfo));
        assertEquals(3, CpuDetector.parseLogicalCores(cpuinfo));
        assertEquals(2, CpuDetector.parsePhysicalCores(cpuinfo));
    }

    @Test
    void derivesHybridSplitExactlyWhenSmtIsPresent() {
        // Meteor Lake Core Ultra 9 185H: 6 hyper-threaded P-cores + 10 E/LP-E cores = 16C / 22T.
        CpuTopology topology = CpuDetector.infer("Intel(R) Core(TM) Ultra 9 185H", 22, 16,
                CpuTopology.Source.WMI);
        assertEquals(6, topology.performanceCores());
        assertEquals(10, topology.efficiencyCores());
        assertTrue(topology.smt());
        assertTrue(topology.hybrid());
        assertEquals(6, topology.foregroundCores());
    }

    @Test
    void appliesLadderForNonSmtHybridParts() {
        // Arrow Lake-HX Core Ultra 9 275HX: 24 cores, 24 threads, 8P + 16E.
        CpuTopology topology = CpuDetector.infer("Intel(R) Core(TM) Ultra 9 275HX", 24, 24,
                CpuTopology.Source.WMI);
        assertEquals(8, topology.performanceCores());
        assertEquals(16, topology.efficiencyCores());
        assertFalse(topology.smt());
        assertFalse(topology.source().splitIsExact(), "an inferred split must be reported as inferred");
    }

    @Test
    void leavesHomogeneousCpusAlone() {
        CpuTopology topology = CpuDetector.infer("AMD Ryzen 7 5800X", 16, 8, CpuTopology.Source.WMI);
        assertEquals(8, topology.performanceCores());
        assertEquals(0, topology.efficiencyCores());
        assertFalse(topology.hybrid());
        assertEquals(8, topology.foregroundCores());
    }

    @Test
    void recognisesHybridFamilies() {
        assertTrue(CpuDetector.looksHybrid("Intel(R) Core(TM) Ultra 9 285HX"));
        assertTrue(CpuDetector.looksHybrid("12th Gen Intel(R) Core(TM) i9-12900HK"));
        assertTrue(CpuDetector.looksHybrid("13th Gen Intel(R) Core(TM) i7-13700K"));
        assertFalse(CpuDetector.looksHybrid("Intel(R) Core(TM) i7-9750H"));
        assertFalse(CpuDetector.looksHybrid("AMD Ryzen 9 7945HX"));
        assertFalse(CpuDetector.looksHybrid(null));
    }

    @Test
    void parsesPowershellCsv() {
        String csv = """
                "Name","NumberOfCores","NumberOfLogicalProcessors"
                "Intel(R) Core(TM) Ultra 9 275HX","24","24"
                """;
        Optional<CpuTopology> topology = CpuDetector.parseWindowsCsv(csv);
        assertTrue(topology.isPresent());
        assertEquals(24, topology.get().physicalCores());
        assertEquals(24, topology.get().logicalCores());
        assertEquals("Intel(R) Core(TM) Ultra 9 275HX", topology.get().brand());
    }

    @Test
    void parsesWmicCsvWithItsDifferentColumnOrder() {
        String csv = """
                Node,Name,NumberOfCores,NumberOfLogicalProcessors
                DESKTOP,Intel(R) Core(TM) Ultra 9 185H,16,22
                """;
        Optional<CpuTopology> topology = CpuDetector.parseWindowsCsv(csv);
        assertTrue(topology.isPresent());
        assertEquals(16, topology.get().physicalCores());
        assertEquals(22, topology.get().logicalCores());
        assertEquals(6, topology.get().performanceCores());
    }

    @Test
    void rejectsUnusableCsv() {
        assertTrue(CpuDetector.parseWindowsCsv(null).isEmpty());
        assertTrue(CpuDetector.parseWindowsCsv("").isEmpty());
        assertTrue(CpuDetector.parseWindowsCsv("something went wrong").isEmpty());
    }

    @Test
    void splitsQuotedCsvFields() {
        List<String> fields = CpuDetector.splitCsv("\"Intel(R) Core, Ultra\",\"24\",\"24\"");
        assertEquals(List.of("Intel(R) Core, Ultra", "24", "24"), fields);
    }

    @Test
    void detectionNeverThrowsOnTheHostMachine() {
        CpuTopology topology = CpuDetector.detect();
        assertTrue(topology.logicalCores() >= 1);
        assertTrue(topology.physicalCores() >= 1);
        assertTrue(topology.performanceCores() >= 1);
    }
}
