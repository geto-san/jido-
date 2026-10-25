package com.geto.jido.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfigApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TileConfigStore store;

    @Test
    void tilesAreReadableWithoutAuthSoFirstRunGetsShortcuts() throws Exception {
        mvc.perform(get("/api/v1/config/tiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").isNotEmpty())
                .andExpect(jsonPath("$.tiles.resolution.enabled").value(true))
                .andExpect(jsonPath("$.tiles.download.enabled").value(false));
    }

    @Test
    void updatingATileRequiresAuth() throws Exception {
        // Unauthenticated writes must fail: an open write lets any caller
        // change the app name that every client renders.
        mvc.perform(put("/api/v1/config/tiles/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", false))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUpdateFlipsEnabledAndKeepsValue() throws Exception {
        String token = login();

        mvc.perform(put("/api/v1/config/tiles/resolution")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiles.resolution.enabled").value(false))
                // Partial update: value was not sent, so the default survives.
                .andExpect(jsonPath("$.tiles.resolution.value").value("auto"));
    }

    @Test
    void appNameUpdateIsTrimmed() throws Exception {
        String token = login();
        String before = store.getAppName();

        mvc.perform(put("/api/v1/config/app-name")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "  my jido  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("my jido"));

        store.updateAppName(before);
    }

    @Test
    void deletingATileRestoresItsDefaultRatherThanDisablingIt() throws Exception {
        String token = login();

        mvc.perform(put("/api/v1/config/tiles/download")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiles.download.enabled").value(true));

        mvc.perform(delete("/api/v1/config/tiles/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiles.download.enabled").value(false));
    }

    @Test
    void blankAppNameIsIgnored() throws Exception {
        String token = login();
        String before = store.getAppName();

        mvc.perform(put("/api/v1/config/app-name")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "   "))))
                .andExpect(status().isOk());

        assertThat(store.getAppName()).isEqualTo(before);
    }

    private String login() throws Exception {
        String email = "cfg-" + UUID.randomUUID() + "@example.com";
        String password = "correct horse battery";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated());
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("accessToken").asText();
    }

    private static String body(Map<String, ?> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}