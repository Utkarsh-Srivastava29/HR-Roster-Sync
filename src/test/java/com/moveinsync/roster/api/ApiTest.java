package com.moveinsync.roster.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Spring context with the in-memory store: authentication, roles, tenant isolation,
 * per-record batch reports and error bodies that never echo personal data.
 */
@SpringBootTest(properties = {"roster.store=memory", "roster.data-dir=target/test-data", "roster.relay-period-ms=50"})
@AutoConfigureMockMvc
class ApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String token(String user) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asText();
    }

    private static String record(String type, String id, int version, String office, String phone) {
        return "{\"type\":\"" + type + "\",\"employeeId\":\"" + id + "\",\"version\":" + version
                + ",\"sourceTime\":\"2026-10-30T10:00:00Z\",\"name\":\"Asha Rao\",\"email\":\"asha." + id + "@acme.example\","
                + "\"phone\":\"" + phone + "\",\"officeCode\":\"" + office + "\",\"shiftCode\":\"GEN\"}";
    }

    @Test
    void batchReportIsPerRecordAndNeverEchoesValues() throws Exception {
        String hr = token("acme-hr");
        String body = "{\"records\":[" + record("CREATE", "E0000101", 1, "BLR1", "+919800000101") + ","
                + record("CREATE", "E0000102", 1, "MARS", "+919800000102") + ","
                + record("CREATE", "E0000103", 1, "BLR1", "98-ABC") + "]}";
        String res = mvc.perform(post("/api/v1/hr/events").header("Authorization", hr)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.APPLIED").value(1))
                .andExpect(jsonPath("$.totals.REJECTED").value(2))
                .andExpect(jsonPath("$.results[1].errors[0].code").value("UNKNOWN_OFFICE"))
                .andExpect(jsonPath("$.results[2].errors[0].field").value("phone"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(res.contains("98-ABC"));
        assertFalse(res.contains("@acme.example"));

        // replay: nothing changes
        mvc.perform(post("/api/v1/hr/events").header("Authorization", hr).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.totals.DUPLICATE").value(1));
    }

    @Test
    void rolesAndTenantIsolation() throws Exception {
        String hr = token("acme-hr");
        mvc.perform(post("/api/v1/hr/events").header("Authorization", hr).contentType(MediaType.APPLICATION_JSON)
                .content("{\"records\":[" + record("CREATE", "E0000001", 1, "BLR1", "+919800000001") + "]}")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/employees/E0000001")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/employees/E0000001").header("Authorization", token("acme-ops"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/employees/E0000001").header("Authorization", token("globex-ops"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/employees/E0000001").header("Authorization", token("acme-emp"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me").header("Authorization", token("acme-emp")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value("E0000001"));
        mvc.perform(post("/api/v1/hr/events").header("Authorization", token("acme-ops")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"records\":[]}")).andExpect(status().isForbidden());
    }

    @Test
    void transportFieldsBelongToTheOperator() throws Exception {
        String hr = token("acme-hr");
        String ops = token("acme-ops");
        mvc.perform(post("/api/v1/hr/events").header("Authorization", hr).contentType(MediaType.APPLICATION_JSON)
                .content("{\"records\":[" + record("CREATE", "E0000009", 1, "BLR1", "+919800000009") + "]}")).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/employees/E0000009/transport").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupPoint\":\"Gate 4\",\"specialAssistance\":[\"WHEELCHAIR\"],\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transport.version").value(1));
        // stale edit -> 409
        mvc.perform(patch("/api/v1/employees/E0000009/transport").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickupPoint\":\"Gate 9\",\"expectedVersion\":0}")).andExpect(status().isConflict());
        // HR tries to set the pickup point: ignored
        String r = "{\"records\":[" + record("UPDATE", "E0000009", 2, "BLR2", "+919800000009").replace("}", ",\"pickupPoint\":\"Elsewhere\"}") + "]}";
        mvc.perform(post("/api/v1/hr/events").header("Authorization", hr).contentType(MediaType.APPLICATION_JSON).content(r))
                .andExpect(jsonPath("$.results[0].ignoredFields[0]").value("PICKUP_POINT"));
        mvc.perform(get("/api/v1/employees/E0000009").header("Authorization", ops))
                .andExpect(jsonPath("$.transport.pickupPoint").value("Gate 4"))
                .andExpect(jsonPath("$.hr.officeCode").value("BLR2"));
    }

    @Test
    void brakeStopsATruncatedExportThroughTheApi() throws Exception {
        String hr = token("acme-hr");
        StringBuilder recs = new StringBuilder("{\"records\":[");
        for (int i = 100; i < 160; i++) recs.append(i == 100 ? "" : ",").append(record("CREATE", "E00001" + i, 1, "BLR1", "+9198000" + (10000 + i)));
        mvc.perform(post("/api/v1/hr/events").header("Authorization", hr).contentType(MediaType.APPLICATION_JSON)
                .content(recs + "]}")).andExpect(status().isOk());

        StringBuilder csv = new StringBuilder("employee_id,version,source_time,name,email,phone,manager_id,office_code,shift_code\n");
        for (int i = 100; i < 120; i++) csv.append("E00001").append(i).append(",1,2026-10-30T10:00:00Z,Asha Rao,asha.E00001").append(i)
                .append("@acme.example,+9198000").append(10000 + i).append(",,BLR1,GEN\n");
        String run = mvc.perform(post("/api/v1/reconciliations").header("Authorization", hr)
                        .contentType("text/csv").content(csv.toString()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = json.readTree(run).get("runId").asText();
        String ops = token("acme-ops");
        JsonNode r = null;
        for (int i = 0; i < 100; i++) {
            r = json.readTree(mvc.perform(get("/api/v1/reconciliations/" + runId).header("Authorization", ops))
                    .andReturn().getResponse().getContentAsString());
            if (!r.get("status").asText().equals("RECEIVED") && !r.get("status").asText().equals("RUNNING")) break;
            Thread.sleep(100);
        }
        assertEquals("BRAKED", r.get("status").asText());
        assertTrue(r.get("brake").get("appearsMissing").asLong() >= 40);
        mvc.perform(get("/api/v1/employees/E00001159").header("Authorization", ops)).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/reconciliations/" + runId + "/approve").header("Authorization", hr)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/reconciliations/" + runId + "/reject").header("Authorization", ops)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }
}
