package dev.coderush.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void loginPageIsAccessible() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void webhookEndpointIsOpenWithoutAuth() throws Exception {
        // Will get 404 since no controller exists yet, but should not redirect to login
        mockMvc.perform(post("/webhook/1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentApiEndpointIsOpenWithoutAuth() throws Exception {
        mockMvc.perform(post("/api/agent/heartbeat")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessUsersPage() throws Exception {
        // 404 is fine — no controller yet. The point is it's not 403.
        mockMvc.perform(get("/users"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "DEVELOPER")
    void developerCannotAccessUsersPage() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotAccessUsersPage() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DEVELOPER")
    void developerCannotAccessAgentsPage() throws Exception {
        mockMvc.perform(get("/agents"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessAgentsPage() throws Exception {
        mockMvc.perform(get("/agents"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotAccessBuildConfigCreate() throws Exception {
        mockMvc.perform(get("/build-configs/new"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DEVELOPER")
    void developerCanAccessBuildConfigCreate() throws Exception {
        mockMvc.perform(get("/build-configs/new"))
                .andExpect(status().isNotFound());
    }
}
