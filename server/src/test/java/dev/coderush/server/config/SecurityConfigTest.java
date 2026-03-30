package dev.coderush.server.config;

import dev.coderush.server.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String tokenFor(String username, String role) {
        return "Bearer " + jwtService.generateAccessToken(username, role);
    }

    @Test
    void unauthenticatedApiRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/builds"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authEndpointsAreOpenWithoutAuth() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"wrong\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookEndpointIsOpenWithoutAuth() throws Exception {
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
    void adminCanAccessUsersApi() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", tokenFor("admin", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void developerCannotAccessUsersApi() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", tokenFor("dev", "DEVELOPER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotAccessUsersApi() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", tokenFor("viewer", "VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanAccessAgentsApi() throws Exception {
        // 404 since no controller — but not 401 or 403
        mockMvc.perform(get("/api/agents")
                        .header("Authorization", tokenFor("admin", "ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void developerCannotAccessAgentsApi() throws Exception {
        mockMvc.perform(get("/api/agents")
                        .header("Authorization", tokenFor("dev", "DEVELOPER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void developerCanAccessBuildConfigsApi() throws Exception {
        // 404 since no controller — but not 401 or 403
        mockMvc.perform(get("/api/build-configs")
                        .header("Authorization", tokenFor("dev", "DEVELOPER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewerCannotAccessBuildConfigsApi() throws Exception {
        mockMvc.perform(get("/api/build-configs")
                        .header("Authorization", tokenFor("viewer", "VIEWER")))
                .andExpect(status().isForbidden());
    }
}
