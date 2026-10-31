package com.tickmap.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tickmap.ingestion.IngestionCounts;
import com.tickmap.ingestion.IngestionRun;
import com.tickmap.ingestion.IngestionRunRepository;
import com.tickmap.sighting.SightingService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @MockitoBean
    SightingService sightingService;

    @Autowired MockMvc mvc;
    @Autowired IngestionRunRepository runs;

    private ResultActions postJson(String body) throws Exception {
        return mvc.perform(post("/api/sightings").contentType(MediaType.APPLICATION_JSON).content(body.replace('\'', '"')));
    }

    private static void isProblem(ResultActions r, int status) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", endsWith("problem+json")))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void beanValidationFailuresListEveryBadField() throws Exception {
        ResultActions r = postJson("{'date':'2999-01-01T00:00:00','location':'','species':'" + "x".repeat(101) + "'}");
        isProblem(r, 400);
        r.andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.instance").value("/api/sightings"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("date"))
                .andExpect(jsonPath("$.errors[1].field").value("location"))
                .andExpect(jsonPath("$.errors[2].field").value("species"));
    }

    @Test
    void malformedJsonIsExplained() throws Exception {
        ResultActions r = postJson("{'date':");
        isProblem(r, 400);
        r.andExpect(jsonPath("$.detail").value("Request body is not valid JSON or does not match the expected object"));
    }

    @Test
    void badDateInBodyNamesTheFieldAndExpectedFormat() throws Exception {
        ResultActions r = postJson("{'date':'14/06/2025','location':'Leeds','species':'Marsh tick'}");
        isProblem(r, 400);
        r.andExpect(jsonPath("$.detail").value(containsString("Field 'date'")))
                .andExpect(jsonPath("$.detail").value(containsString("2025-06-14T17:30:00")));
    }

    @Test
    void missingBodyIs400() throws Exception {
        isProblem(mvc.perform(post("/api/sightings").contentType(MediaType.APPLICATION_JSON)), 400);
    }

    @Test
    void wrongPathVariableTypeIs400() throws Exception {
        ResultActions r = mvc.perform(get("/api/sightings/abc"));
        isProblem(r, 400);
        r.andExpect(jsonPath("$.detail").value("Parameter 'id' must be a valid Long, got 'abc'"));
    }

    @Test
    void unknownSortPropertyIs400NotA500() throws Exception {
        IngestionRun run = IngestionRun.start(IngestionRun.Trigger.MANUAL);
        run.succeed(IngestionCounts.ZERO, null);
        run = runs.save(run);
        ResultActions r = mvc.perform(get("/api/ingestion/runs/{id}/rejected", run.getId()).param("sort", "bogus"));
        isProblem(r, 400);
        r.andExpect(jsonPath("$.detail").value(containsString("bogus")));
    }

    @Test
    void unknownUrlIs404() throws Exception {
        isProblem(mvc.perform(get("/api/nope")), 404);
    }

    @Test
    void wrongMethodIs405() throws Exception {
        isProblem(mvc.perform(delete("/api/sightings")), 405);
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        isProblem(mvc.perform(post("/api/sightings").contentType(MediaType.TEXT_PLAIN).content("hello")), 415);
    }

    @Test
    void responseStatusExceptionsGetTheSameShape() throws Exception {
        when(sightingService.find(any())).thenReturn(Optional.empty());
        ResultActions r = mvc.perform(get("/api/sightings/42"));
        isProblem(r, 404);
        r.andExpect(jsonPath("$.detail").value("No sighting with id 42"));
    }

    @Test
    void unexpectedErrorsAre500WithAReferenceAndNoInternals() throws Exception {
        when(sightingService.find(any())).thenThrow(new IllegalStateException("db password is hunter2"));
        ResultActions r = mvc.perform(get("/api/sightings/1"));
        isProblem(r, 500);
        r.andExpect(jsonPath("$.errorId").value(notNullValue()))
                .andExpect(jsonPath("$.detail").value(containsString("Quote reference")))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void databaseConstraintViolationsAre409() throws Exception {
        when(sightingService.create(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint ux_fingerprint"));
        ResultActions r = postJson("{'date':'2025-06-14T17:30:00','location':'Leeds','species':'Marsh tick'}");
        isProblem(r, 409);
        r.andExpect(content().string(not(containsString("ux_fingerprint"))));
    }
}
