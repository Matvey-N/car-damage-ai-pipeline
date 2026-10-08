package com.cardamage.web;

import com.cardamage.core.inspect.Views;
import com.cardamage.core.miniapp.InitDataValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mini App backend with a test bot token; polling is off, so nothing is sent to Telegram. */
@SpringBootTest(properties = {"pipeline.model-client=stub", "anthropic.api-key=",
        "telegram.bot-token=" + MiniAppControllerTest.TOKEN, "telegram.polling=false",
        "telegram.miniapp.photos-per-hour=20",
        "pipeline.storage.dir=target/test-store-${random.uuid}"})
@AutoConfigureMockMvc
class MiniAppControllerTest {

    static final String TOKEN = "123456:test-token";
    private static final String H = "X-Telegram-Init-Data";

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    private static MockMultipartFile jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return new MockMultipartFile("images", "car.jpg", "image/jpeg", out.toByteArray());
    }

    private static String initData(long userId) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("auth_date", String.valueOf(System.currentTimeMillis() / 1000));
        f.put("user", "{\"id\":" + userId + ",\"first_name\":\"Test\"}");
        return new InitDataValidator(TOKEN, 3600).sign(f);
    }

    private JsonNode analyze(long user, String... params) throws Exception {
        var request = multipart("/api/v1/miniapp/analyze").file(jpeg()).file(jpeg()).header(H, initData(user));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    @Test
    void configListsTheViews() throws Exception {
        mvc.perform(get("/api/v1/miniapp/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.views.front").value("Спереди"));
    }

    @Test
    void requestWithoutTelegramSignatureIs401() throws Exception {
        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/miniapp/inspections").header(H, initData(1).replace("Test", "Evil")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analysisIsStoredAndShownInTheHistory() throws Exception {
        JsonNode inspection = analyze(10, "views", "front", "views", "rear", "title", "Golf");
        String id = inspection.get("id").asText();
        assertEquals(2, inspection.get("photos").size());
        assertEquals(Views.name("rear"), inspection.get("photos").get(1).get("view_name").asText());
        assertEquals(1, inspection.get("summary").get("damages").size());

        mvc.perform(get("/api/v1/miniapp/inspections").header(H, initData(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].title").value("Golf"))
                .andExpect(jsonPath("$[0].photos").value(2));
        mvc.perform(get("/api/v1/miniapp/inspections/" + id + "/photos/0").header(H, initData(10)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    void anotherUserCannotSeeOrDeleteIt() throws Exception {
        String id = analyze(11).get("id").asText();
        mvc.perform(get("/api/v1/miniapp/inspections/" + id).header(H, initData(12))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/miniapp/inspections/" + id).header(H, initData(12))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/miniapp/inspections").header(H, initData(12)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void userCorrectionReplacesTheListAndBadOnesAreRefused() throws Exception {
        String id = analyze(13).get("id").asText();
        mvc.perform(put("/api/v1/miniapp/inspections/" + id + "/photos/0/damages").header(H, initData(13))
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos[0].edited").value(true))
                .andExpect(jsonPath("$.photos[0].damages.length()").value(0));
        // "flood" is in no taxonomy (dent became valid with the general profile)
        String bad = "[{\"damage_type\":\"flood\",\"part\":\"door\",\"severity\":\"minor\",\"action\":\"repair\","
                + "\"confidence\":0.5,\"bounding_box\":[0.1,0.1,0.1,0.1]}]";
        mvc.perform(put("/api/v1/miniapp/inspections/" + id + "/photos/1/damages").header(H, initData(13))
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnInspectionIsComparedWithThePickupOne() throws Exception {
        String before = analyze(14, "kind", "before", "views", "front", "views", "rear").get("id").asText();
        // the stub sees the same scratch on every photo: nothing is new on the same views
        JsonNode after = analyze(14, "kind", "after", "before_id", before, "views", "front", "views", "left");
        assertEquals(0, after.get("comparison").get("new").asInt());
        assertEquals(1, after.get("comparison").get("not_compared").asInt(), "no pickup photo of the left side");
        assertEquals("existing", after.get("photos").get(0).get("comparison").get(0).asText());

        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg()).header(H, initData(14))
                        .param("kind", "after").param("before_id", after.get("id").asText()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pdfReportIsGenerated() throws Exception {
        String id = analyze(15).get("id").asText();
        byte[] pdf = mvc.perform(get("/api/v1/miniapp/inspections/" + id + "/report.pdf").header(H, initData(15)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(new String(pdf, 0, 8, java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    @Test
    void deleteRemovesTheInspection() throws Exception {
        String id = analyze(16).get("id").asText();
        mvc.perform(delete("/api/v1/miniapp/inspections/" + id).header(H, initData(16))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/miniapp/inspections/" + id).header(H, initData(16))).andExpect(status().isNotFound());
    }

    @Test
    void backgroundAnalysisReportsProgressAndEndsWithTheInspection() throws Exception {
        String body = mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg()).file(jpeg())
                        .param("async", "true").param("mode", "relook").header(H, initData(30)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.total").value(2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String jobId = json.readTree(body).get("job_id").asText();

        // another user does not see the job
        mvc.perform(get("/api/v1/miniapp/jobs/" + jobId).header(H, initData(31))).andExpect(status().isNotFound());

        JsonNode job = null;
        for (int i = 0; i < 100; i++) {
            job = json.readTree(mvc.perform(get("/api/v1/miniapp/jobs/" + jobId).header(H, initData(30)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            if (job.get("status").asText().equals("done")) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals("done", job.get("status").asText());
        assertEquals(2, job.get("done").asInt());
        assertEquals("relook", job.get("inspection").get("mode").asText());
        assertEquals(2, job.get("inspection").get("photos").size());
        assertEquals(1, job.get("inspection").get("summary").get("damages").size());
        mvc.perform(get("/api/v1/miniapp/jobs").header(H, initData(30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownModeIsRefused() throws Exception {
        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg()).param("mode", "zoom").header(H, initData(32)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctionMayUseTheExtendedTaxonomy() throws Exception {
        String id = analyze(33).get("id").asText();
        String dent = "[{\"damage_type\":\"dent\",\"part\":\"trunk\",\"severity\":\"moderate\",\"action\":\"repair\","
                + "\"confidence\":0.8,\"bounding_box\":[0.1,0.1,0.2,0.2]}]";
        mvc.perform(put("/api/v1/miniapp/inspections/" + id + "/photos/0/damages").header(H, initData(33))
                        .contentType(MediaType.APPLICATION_JSON).content(dent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos[0].damages[0].damage_type").value("dent"));
    }

    @Test
    void limitPerUserIsEnforced() throws Exception {
        for (int i = 0; i < 10; i++) {
            analyze(17);   // 20 photos = the limit
        }
        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg()).header(H, initData(17)))
                .andExpect(status().isTooManyRequests());
    }
}
