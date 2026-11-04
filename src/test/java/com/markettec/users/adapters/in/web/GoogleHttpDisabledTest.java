package com.markettec.users.adapters.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GoogleHttpDisabledTest {
    @Autowired MockMvc mvc;

    @Test void defaultApplicationDoesNotExposeGoogleAuthRoutes() throws Exception {
        for (String path : new String[] {"/v1/auth/attempts", "/v1/auth/google",
                "/v1/auth/google/registrations"}) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test void defaultApplicationDoesNotExposeB3AuthRoutes() throws Exception {
        mvc.perform(get("/v1/auth/session")).andExpect(status().isNotFound());
        for (String path : new String[] {"/v1/auth/refresh", "/v1/auth/logout"}) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }
    }
}
