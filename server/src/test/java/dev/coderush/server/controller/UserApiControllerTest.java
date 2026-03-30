package dev.coderush.server.controller;

import dev.coderush.server.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class UserApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken() {
        return "Bearer " + jwtService.generateAccessToken("admin", "ADMIN");
    }

    private String developerToken() {
        return "Bearer " + jwtService.generateAccessToken("dev", "DEVELOPER");
    }

    @Test
    void adminCanListUsers() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void adminCanCreateUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users")
                        .header("Authorization", adminToken())
                        .contentType("application/json")
                        .content("{\"username\":\"testdev\",\"password\":\"pass123\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("testdev", body.get("username").asText());
        assertEquals("DEVELOPER", body.get("role").asText());
        assertNotNull(body.get("id"));
    }

    @Test
    void createDuplicateUserReturnsBadRequest() throws Exception {
        // admin already exists from startup
        mockMvc.perform(post("/api/users")
                        .header("Authorization", adminToken())
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"pass\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanDeleteUser() throws Exception {
        // Create a user first
        MvcResult created = mockMvc.perform(post("/api/users")
                        .header("Authorization", adminToken())
                        .contentType("application/json")
                        .content("{\"username\":\"todelete\",\"password\":\"pass\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/api/users/" + id)
                        .header("Authorization", adminToken()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteNonexistentUserReturns404() throws Exception {
        mockMvc.perform(delete("/api/users/99999")
                        .header("Authorization", adminToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCanChangePassword() throws Exception {
        // Create a user first
        MvcResult created = mockMvc.perform(post("/api/users")
                        .header("Authorization", adminToken())
                        .contentType("application/json")
                        .content("{\"username\":\"pwdchange\",\"password\":\"oldpass\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(put("/api/users/" + id + "/password")
                        .header("Authorization", adminToken())
                        .contentType("application/json")
                        .content("{\"newPassword\":\"newpass\"}"))
                .andExpect(status().isOk());

        // Verify the new password works by logging in
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"pwdchange\",\"password\":\"newpass\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void developerCannotAccessUserApi() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", developerToken()))
                .andExpect(status().isForbidden());
    }
}
