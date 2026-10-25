package com.geto.jido.server.download;

import com.geto.jido.server.IntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class DownloadApiTest extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DownloadRepository downloads;

    @Test
    void enqueueAcceptsAndRecordsAPendingJob() throws Exception {
        String token = registerAndLogin();
        String url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

        MvcResult result = mvc.perform(post("/api/v1/downloads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("url", url))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.platform").value("youtube"))
                .andExpect(jsonPath("$.sourceUrl").value(url))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();

        String id = id(result);
        Download persisted = downloads.findById(UUID.fromString(id)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DownloadStatus.PENDING);
    }

    @Test
    void retryingWithTheSameClientRefReturnsTheOriginalJob() throws Exception {
        String token = registerAndLogin();
        String ref = "clip-" + UUID.randomUUID();

        MvcResult first = mvc.perform(post("/api/v1/downloads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "url", "https://youtu.be/abc",
                                "clientRef", ref))))
                .andExpect(status().isAccepted())
                .andReturn();

        MvcResult second = mvc.perform(post("/api/v1/downloads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "url", "https://youtu.be/different",
                                "clientRef", ref))))
                .andExpect(status().isOk())
                .andReturn();

        // The whole point of clientRef: a lost response must not cost a
        // duplicate multi-gigabyte transfer.
        assertThat(id(second)).isEqualTo(id(first));
        assertThat(downloads.count()).isEqualTo(downloads.count());
    }

    @Test
    void rejectsNonHttpUrls() throws Exception {
        String token = registerAndLogin();

        mvc.perform(post("/api/v1/downloads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("url", "file:///etc/passwd"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oneUserCannotReadAnotherUsersDownload() throws Exception {
        String aliceToken = registerAndLogin();
        MvcResult created = mvc.perform(post("/api/v1/downloads")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("url", "https://youtu.be/abc"))))
                .andExpect(status().isAccepted())
                .andReturn();
        String aliceId = id(created);

        String bobToken = registerAndLogin();

        // A UUID job id is unguessable but not secret. Without the user scope
        // on every read, any authenticated user could read anyone's history.
        mvc.perform(get("/api/v1/downloads/" + aliceId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/downloads/" + aliceId)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void listOnlyReturnsTheCallersJobs() throws Exception {
        String aliceToken = registerAndLogin();
        mvc.perform(post("/api/v1/downloads")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("url", "https://youtu.be/alice-one"))));

        String bobToken = registerAndLogin();
        mvc.perform(post("/api/v1/downloads")
                .header("Authorization", "Bearer " + bobToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("url", "https://youtu.be/alice-two"))));

        String body = mvc.perform(get("/api/v1/downloads")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("alice-one");
        assertThat(body).contains("alice-two");
    }

    @Test
    void listClampsAnOversizedPageRequest() throws Exception {
        String token = registerAndLogin();

        // Bounded rather than rejected: the client should still get an answer.
        mvc.perform(get("/api/v1/downloads?size=100000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void cancellingAFinishedJobIsAConflictNotASilentSuccess() throws Exception {
        String token = registerAndLogin();
        MvcResult created = mvc.perform(post("/api/v1/downloads")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("url", "https://youtu.be/abc"))))
                .andReturn();
        String id = id(created);

        Download download = downloads.findById(UUID.fromString(id)).orElseThrow();
        download.markCompleted("users/x/clip.mp4", "clip.mp4", 1024);
        downloads.save(download);

        mvc.perform(delete("/api/v1/downloads/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"));
    }

    @Test
    void enqueueRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/downloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("url", "https://youtu.be/abc"))))
                .andExpect(status().isUnauthorized());
    }

    private String registerAndLogin() throws Exception {
        String email = "dl-" + UUID.randomUUID() + "@example.com";
        String password = "correct horse battery";
        MvcResult registered = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static String id(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static String body(Map<String, ?> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}