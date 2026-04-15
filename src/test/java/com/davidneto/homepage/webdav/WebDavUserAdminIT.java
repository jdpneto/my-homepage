package com.davidneto.homepage.webdav;

import com.davidneto.homepage.repository.WebDavUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebDavUserAdminIT {

    @Autowired MockMvc mvc;
    @Autowired WebDavUserRepository repo;

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_can_create_list_and_delete_user() throws Exception {
        mvc.perform(post("/admin/webdav-users").with(csrf())
                        .param("username", "alice")
                        .param("password", "password1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/webdav-users"));

        mvc.perform(get("/admin/webdav-users"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("alice")));

        Long id = repo.findByUsername("alice").orElseThrow().getId();

        mvc.perform(post("/admin/webdav-users/" + id + "/reset-password").with(csrf())
                        .param("password", "new-password-abc"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/admin/webdav-users/" + id + "/clear-data").with(csrf()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/admin/webdav-users/" + id + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(repo.findById(id)).isEmpty();
    }

    @Test
    void unauthenticated_request_is_redirected_to_login() throws Exception {
        mvc.perform(get("/admin/webdav-users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }
}
