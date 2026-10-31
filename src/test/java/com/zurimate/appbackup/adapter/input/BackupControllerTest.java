package com.zurimate.appbackup.adapter.input;

import com.zurimate.appbackup.config.GlobalExceptionHandler;
import com.zurimate.appbackup.exception.NotFoundException;
import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BackupControllerTest {
    private BackupSchedulerService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BackupSchedulerService.class);
        mvc = MockMvcBuilders.standaloneSetup(new BackupController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void invalidBodyIsA400NamingTheField() throws Exception {
        mvc.perform(post("/backup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"schedulerType":"DAILY","destination":"/b",
                         "dbConfig":{"dbType":"POSTGRESQL","port":70000,"dbName":"x","password":"Sup3rSecret"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("port")));
    }

    @Test
    void malformedJsonNeverEchoesTheBody() throws Exception {
        mvc.perform(post("/backup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbConfig\":{\"password\":\"Sup3rSecret\",\"dbType\":\"NOT_A_DB\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("Sup3rSecret"))))
                .andExpect(jsonPath("$.message").value("Malformed request body or parameter"));
    }

    @Test
    void validRegistrationIsCreated() throws Exception {
        mvc.perform(post("/backup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"schedulerType":"DAILY","destination":"/b",
                         "dbConfig":{"dbType":"REDIS","port":6379,"dbName":"0"}}"""))
                .andExpect(status().isCreated());
        verify(service).createScheduledBackup(any());
    }

    @Test
    void readAndTriggerEndpoints() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.listBackups()).thenReturn(List.of());
        mvc.perform(get("/backups")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        mvc.perform(get("/backups/{id}/runs", id).param("limit", "5")).andExpect(status().isOk());
        mvc.perform(post("/backups/{id}/run", id)).andExpect(status().isAccepted());
        mvc.perform(get("/init")).andExpect(status().isAccepted());
        mvc.perform(delete("/backups/{id}", id)).andExpect(status().isOk());
        verify(service).listRuns(id, 5);
        verify(service).triggerBackup(id);
        verify(service).initiateAllBackups();
        verify(service).deleteBackup(id);
    }

    @Test
    void errorsMapToTheRightStatus() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new NotFoundException("Backup " + id + " not found")).when(service).triggerBackup(id);
        mvc.perform(post("/backups/{id}/run", id)).andExpect(status().isNotFound());
        mvc.perform(get("/backups/not-a-uuid")).andExpect(status().isBadRequest());
        when(service.listBackups()).thenThrow(new IllegalStateException("db down"));
        mvc.perform(get("/backups")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }
}
