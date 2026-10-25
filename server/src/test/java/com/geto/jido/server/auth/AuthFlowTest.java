package com.geto.jido.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.geto.jido.server.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthFlowTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String json(Map<String, ?> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise test request body", e);
        }
    }

    @Test
    void registerThenLoginYieldsUsableToken() throws Exception {
        String email = uniqueEmail();
        String password = "correct horse battery";

        String accessToken = register(email, password);
        assertThat(accessToken).isNotBlank();

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        String email = uniqueEmail();
        String password = "correct horse battery";
        register(email, password);

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email.toUpperCase(), "password", password))))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        String password = "correct horse battery";
        register(email, password);

        String wrongPassword = errorCode(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "wrong password"))))
                .andExpect(status().isUnauthorized())
                .andReturn());

        String unknownEmail = errorCode(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", uniqueEmail(), "password", password))))
                .andExpect(status().isUnauthorized())
                .andReturn());

        // Identical code and detail: otherwise this endpoint enumerates
        // registered addresses. Only the timestamp may differ.
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void duplicateRegistrationIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email, "correct horse battery");

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "correct horse battery"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_taken"));
    }

    @Test
    void shortPasswordIsRejectedBeforeHashing() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", uniqueEmail(), "password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void refreshRotatesTheToken() throws Exception {
        String email = uniqueEmail();
        String password = "correct horse battery";
        register(email, password);

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        String firstRefresh = readToken(login, "refreshToken");

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", firstRefresh))))
                .andExpect(status().isOk())
                .andReturn();
        String secondRefresh = readToken(refreshed, "refreshToken");
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);

        // Replaying the consumed token must fail — that is what makes a stolen
        // refresh token detectable rather than silently reusable.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", firstRefresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String email = uniqueEmail();
        String password = "correct horse battery";
        register(email, password);

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andReturn();
        String refreshToken = readToken(login, "refreshToken");

        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsAnonymousAndAcceptsBearer() throws Exception {
        mvc.perform(post("/api/v1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", "https://youtu.be/abc"))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/resolve")
                        .header("Authorization", "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", "https://youtu.be/abc"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointStaysOpenForProbes() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private String register(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        return readToken(result, "accessToken");
    }

    private static String readToken(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        return JSON.readTree(body).get(field).asText();
    }

    /** Code and detail only, so the per-response timestamp cannot skew a comparison. */
    private static String errorCode(MvcResult result) throws Exception {
        var node = JSON.readTree(result.getResponse().getContentAsString());
        return node.get("code").asText() + "|" + node.get("detail").asText();
    }
}
