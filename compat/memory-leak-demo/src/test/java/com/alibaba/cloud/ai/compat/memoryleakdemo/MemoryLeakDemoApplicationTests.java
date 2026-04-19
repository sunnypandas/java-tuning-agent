package com.alibaba.cloud.ai.compat.memoryleakdemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.alibaba.cloud.ai.compat.memoryleakdemo.web.LeakController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MemoryLeakDemoApplication.class)
class MemoryLeakDemoApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void contextLoads() {
	}

	@Test
	void leakControllerBeanShouldBePresent() {
		assertThat(applicationContext.getBean(LeakController.class)).isNotNull();
	}

}
