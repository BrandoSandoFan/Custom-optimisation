package dev.brandosandofan.spectune.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GpuInfoTest {

    @Test
    void recognisesALaptopRtx5080AsHighTier() {
        GpuInfo gpu = GpuInfo.of("NVIDIA Corporation", "NVIDIA GeForce RTX 5080 Laptop GPU/PCIe/SSE2", "4.6.0");
        assertFalse(gpu.integrated());
        assertEquals(GpuInfo.Tier.HIGH, gpu.tier());
    }

    @Test
    void ranksWithinTheNvidiaStack() {
        assertEquals(GpuInfo.Tier.HIGH, GpuInfo.of("NVIDIA", "NVIDIA GeForce RTX 4070", "4.6").tier());
        assertEquals(GpuInfo.Tier.MID, GpuInfo.of("NVIDIA", "NVIDIA GeForce RTX 5050", "4.6").tier());
        assertEquals(GpuInfo.Tier.HIGH, GpuInfo.of("NVIDIA", "NVIDIA GeForce RTX 3080", "4.6").tier());
        assertEquals(GpuInfo.Tier.MID, GpuInfo.of("NVIDIA", "NVIDIA GeForce RTX 2060", "4.6").tier());
        assertEquals(GpuInfo.Tier.LOW, GpuInfo.of("NVIDIA", "NVIDIA GeForce GTX 1060", "4.6").tier());
    }

    @Test
    void flagsIntegratedGraphics() {
        assertTrue(GpuInfo.of("Intel", "Intel(R) UHD Graphics 770", "4.6").integrated());
        assertTrue(GpuInfo.of("Intel", "Intel(R) Arc(TM) Graphics", "4.6").integrated());
        assertTrue(GpuInfo.of("Mesa", "llvmpipe (LLVM 15.0.7, 256 bits)", "4.5").integrated());
        assertTrue(GpuInfo.of("AMD", "AMD Radeon(TM) Graphics", "4.6").integrated());
    }

    @Test
    void doesNotMistakeDiscreteArcForAnIgpu() {
        GpuInfo gpu = GpuInfo.of("Intel", "Intel(R) Arc(TM) A770 Graphics", "4.6");
        assertFalse(gpu.integrated(), "discrete Arc must not be reported as integrated");
    }

    @Test
    void ranksDiscreteIntelArcInsteadOfLeavingItUnknown() {
        assertEquals(GpuInfo.Tier.MID, GpuInfo.of("Intel", "Intel(R) Arc(TM) A770 Graphics", "4.6").tier());
        assertEquals(GpuInfo.Tier.MID, GpuInfo.of("Intel", "Intel(R) Arc(TM) A750 Graphics", "4.6").tier());
        assertEquals(GpuInfo.Tier.MID, GpuInfo.of("Intel", "Intel(R) Arc(TM) B580 Graphics", "4.6").tier());
        assertEquals(GpuInfo.Tier.LOW, GpuInfo.of("Intel", "Intel(R) Arc(TM) A380 Graphics", "4.6").tier());
    }

    @Test
    void ranksRdna4DespiteItsShorterSuffixDigits() {
        // RX 9070/9060 dropped the third suffix digit older Radeon generations used (7900, 7600, ...).
        assertEquals(GpuInfo.Tier.HIGH, GpuInfo.of("AMD", "AMD Radeon RX 9070 XT", "4.6").tier());
        assertEquals(GpuInfo.Tier.MID, GpuInfo.of("AMD", "AMD Radeon RX 9060 XT", "4.6").tier());
        assertEquals(GpuInfo.Tier.HIGH, GpuInfo.of("AMD", "AMD Radeon RX 7900 XTX", "4.6").tier());
        assertEquals(GpuInfo.Tier.MID, GpuInfo.of("AMD", "AMD Radeon RX 7600", "4.6").tier());
    }

    @Test
    void handlesMissingStringsWithoutThrowing() {
        GpuInfo gpu = GpuInfo.of(null, null, null);
        assertEquals(GpuInfo.Tier.UNKNOWN, gpu.tier());
        assertFalse(gpu.integrated());
    }
}
