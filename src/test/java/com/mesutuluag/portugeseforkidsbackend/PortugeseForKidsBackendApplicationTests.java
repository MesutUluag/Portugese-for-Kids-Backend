package com.mesutuluag.portugeseforkidsbackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"image.cache.gcs.enabled=false",
		"firestore.enabled=false",
		"spring.ai.vertex.ai.gemini.project-id=test-project"
})
class PortugeseForKidsBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
