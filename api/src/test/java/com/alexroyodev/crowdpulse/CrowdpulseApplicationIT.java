package com.alexroyodev.crowdpulse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.alexroyodev.crowdpulse.alert.AlertProperties;
import com.alexroyodev.crowdpulse.alert.AlertProperties.Threshold;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CrowdpulseApplicationIT {

	@Test
	void contextLoads() {
	}

	@Test
	void alertThresholdsAreTheOnesOfSpec002(@Autowired AlertProperties alerts) {
		assertThat(alerts.warning()).isEqualTo(new Threshold(85, 80));
		assertThat(alerts.critical()).isEqualTo(new Threshold(95, 90));
	}

}
