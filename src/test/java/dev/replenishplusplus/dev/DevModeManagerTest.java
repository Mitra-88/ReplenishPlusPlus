package dev.replenishplusplus.dev;

import dev.replenishplusplus.dev.DevModeManager.SparkAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DevModeManagerTest {

    @Test
    void sparkProfilerCommandsAreClassified() {
        assertEquals(SparkAction.TOGGLE, DevModeManager.parseSparkCommand("/spark profiler"));
        assertEquals(SparkAction.START, DevModeManager.parseSparkCommand("/spark profiler start"));
        assertEquals(SparkAction.START, DevModeManager.parseSparkCommand("/spark profiler start --timeout 60"));
        assertEquals(SparkAction.STOP, DevModeManager.parseSparkCommand("/spark profiler stop"));
        assertEquals(SparkAction.TOGGLE, DevModeManager.parseSparkCommand("/spark profiler --memory true"));
        assertEquals(SparkAction.START, DevModeManager.parseSparkCommand("spark profiler start"));
        assertEquals(SparkAction.TOGGLE, DevModeManager.parseSparkCommand("  /Spark Profiler  "));
    }

    @Test
    void unrelatedCommandsAreIgnored() {
        assertEquals(SparkAction.OTHER, DevModeManager.parseSparkCommand("/spark heapdump"));
        assertEquals(SparkAction.OTHER, DevModeManager.parseSparkCommand("/spark tps"));
        assertEquals(SparkAction.OTHER, DevModeManager.parseSparkCommand("/stop"));
        assertEquals(SparkAction.OTHER, DevModeManager.parseSparkCommand("/spark profiler interrupt"));
        assertEquals(SparkAction.OTHER, DevModeManager.parseSparkCommand(null));
    }

    @Test
    void profilerDurationsAreParsed() {
        assertEquals(60L, DevModeManager.parseProfilerDurationSeconds("/spark profiler start 60"));
        assertEquals(300L, DevModeManager.parseProfilerDurationSeconds("/spark profiler start --timeout 300"));
        assertEquals(45L, DevModeManager.parseProfilerDurationSeconds("spark profiler start --timeout 45 --memory true"));
        assertEquals(0L, DevModeManager.parseProfilerDurationSeconds("/spark profiler start"));
        assertEquals(0L, DevModeManager.parseProfilerDurationSeconds("/spark profiler stop 60"));
        assertEquals(0L, DevModeManager.parseProfilerDurationSeconds("/spark profiler start --timeout"));
        assertEquals(0L, DevModeManager.parseProfilerDurationSeconds("/spark tps"));
        assertEquals(0L, DevModeManager.parseProfilerDurationSeconds(null));
    }

    @Test
    void profilerDurationsAreCapped() {
        assertEquals(86_400L, DevModeManager.parseProfilerDurationSeconds("/spark profiler start 99999999"));
    }
}
