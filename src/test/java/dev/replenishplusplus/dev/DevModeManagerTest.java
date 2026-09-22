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
}
