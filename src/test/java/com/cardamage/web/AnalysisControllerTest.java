package com.cardamage.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the full Spring context with the stub model client
 * (pipeline.model-client=stub is the default) and calls the endpoints.
 */
// Always the stub here, whatever the environment says: with PIPELINE_MODEL_CLIENT=anthropic set
// (as for a benchmark run) the tests would otherwise call the real, paid API.
@SpringBootTest(properties = {"pipeline.model-client=stub", "anthropic.api-key="})
@AutoConfigureMockMvc
class AnalysisControllerTest {

    @Autowired
    private MockMvc mvc;

    /** A real (tiny) JPEG: the service decodes uploads before sending them to the model. */
    private static MockMultipartFile jpeg(String param) throws Exception {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new MockMultipartFile(param, "car.jpg", "image/jpeg", out.toByteArray());
    }

    @Test
    void unreadableImageIs400() throws Exception {
        MockMultipartFile broken = new MockMultipartFile("image", "car.jpg", "image/jpeg", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/v1/analyze").file(broken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void singleImageWithStubReturnsSuccess() throws Exception {
        mvc.perform(multipart("/api/v1/analyze").file(jpeg("image")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.damages[0].damage_type").value("scratch"))
                .andExpect(jsonPath("$.damages[0].bounding_box.length()").value(4))
                .andExpect(jsonPath("$.attempts").value(1));
    }

    @Test
    void unsupportedImageTypeIs400() throws Exception {
        MockMultipartFile gif = new MockMultipartFile("image", "car.gif", "image/gif", new byte[]{1});
        mvc.perform(multipart("/api/v1/analyze").file(gif))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void demoRejectsTooFewImages() throws Exception {
        mvc.perform(multipart("/api/v1/demo/analyze").file(jpeg("images")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void demoMergesThreePhotos() throws Exception {
        mvc.perform(multipart("/api/v1/demo/analyze")
                        .file(jpeg("images")).file(jpeg("images")).file(jpeg("images")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.damages.length()").value(1));
    }

    @Test
    void infoShowsStubMode() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model_client").value("stub"))
                .andExpect(jsonPath("$.prompt_version").value("v3"));
    }
}
