package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LeakControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void allocateAndStatsAndClearShouldReturnStructuredResponses() throws Exception {
		mockMvc.perform(post("/api/leak/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"entries":2,"payloadKb":16,"tag":"demo-run"}
						"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retainedEntries").value(2))
			.andExpect(jsonPath("$.retainedBytesEstimate").value(32768));

		mockMvc.perform(get("/api/leak/stats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.allocationRequests").value(1))
			.andExpect(jsonPath("$.recentAllocations[0].tag").value("demo-run"));

		mockMvc.perform(post("/api/leak/clear"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.clearedEntries").value(2))
			.andExpect(jsonPath("$.clearedBytesEstimate").value(32768))
			.andExpect(jsonPath("$.remainingEntries").value(0));

		mockMvc.perform(get("/api/leak/stats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retainedEntries").value(0))
			.andExpect(jsonPath("$.retainedBytesEstimate").value(0))
			.andExpect(jsonPath("$.allocationRequests").value(1))
			.andExpect(jsonPath("$.recentAllocations[0].tag").value("demo-run"));
	}

	@Test
	void allocateShouldRejectOversizedRequests() throws Exception {
		mockMvc.perform(post("/api/leak/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"entries":2001,"payloadKb":16,"tag":"too-large"}
						"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allocateShouldRejectZeroEntries() throws Exception {
		mockMvc.perform(post("/api/leak/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"entries":0,"payloadKb":16,"tag":"demo-run"}
						"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allocateShouldRejectZeroPayloadKb() throws Exception {
		mockMvc.perform(post("/api/leak/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"entries":2,"payloadKb":0,"tag":"demo-run"}
						"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allocateShouldRejectPayloadKbAboveUpperBound() throws Exception {
		mockMvc.perform(post("/api/leak/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"entries":2,"payloadKb":1025,"tag":"demo-run"}
						"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allocateShouldRejectBlankTag() throws Exception {
		mockMvc.perform(post("/api/leak/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"entries":2,"payloadKb":16,"tag":"   "}
						"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allocateShouldRejectMissingTag() throws Exception {
		mockMvc.perform(post("/api/leak/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"entries":2,"payloadKb":16}
						"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allocateShouldRejectMissingRequiredFields() throws Exception {
		mockMvc.perform(post("/api/leak/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{}
						"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void rawAllocateAndClearShouldWorkIndependently() throws Exception {
		mockMvc.perform(post("/api/leak/raw/allocate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"entries":2,"payloadKb":8,"tag":"raw-tag"}
						"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retainedEntries").value(2));

		mockMvc.perform(get("/api/leak/raw/stats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retainedBytesEstimate").value(16384));

		mockMvc.perform(post("/api/leak/raw/clear"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.remainingEntries").value(0));
	}

	@Test
	void churnShouldReturnTiming() throws Exception {
		mockMvc.perform(post("/api/leak/churn")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"iterations":5000,"payloadBytes":1024}
						"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.iterations").value(5000))
			.andExpect(jsonPath("$.payloadBytes").value(1024))
			.andExpect(jsonPath("$.hint").exists());
	}

	@Test
	void validationGuideShouldExposeScenarios() throws Exception {
		mockMvc.perform(get("/api/leak/validation-guide"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.springApplicationName").value("memory-leak-demo"))
			.andExpect(jsonPath("$.scenarios[0].id").value("retained-records-heap-pressure"))
			.andExpect(jsonPath("$.scenarios[4].id").value("young-gc-churn"));
	}

	@Test
	void deadlockTriggerShouldBeIdempotent() throws Exception {
		mockMvc.perform(post("/api/leak/deadlock/trigger"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startedFresh").value(true));

		mockMvc.perform(post("/api/leak/deadlock/trigger"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startedFresh").value(false))
			.andExpect(jsonPath("$.alreadyTriggered").value(true));

		mockMvc.perform(get("/api/leak/deadlock/status"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deadlockFeatureEnabled").value(true))
			.andExpect(jsonPath("$.deadlockAlreadyTriggered").value(true));
	}

}
