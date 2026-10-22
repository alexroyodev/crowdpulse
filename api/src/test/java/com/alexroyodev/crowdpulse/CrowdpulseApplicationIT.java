package com.alexroyodev.crowdpulse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The alert thresholds moved to the processor with the rules that use them (ProcessorApplicationIT). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CrowdpulseApplicationIT {

	@Test
	void contextLoads() {
	}

}
