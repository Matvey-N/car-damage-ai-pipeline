package com.cardamage.web;

import com.cardamage.core.miniapp.InitDataValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mini App backend with a test bot token; polling is off, so nothing is sent to Telegram. */
@SpringBootTest(properties = {"pipeline.model-client=stub", "anthropic.api-key=",
        "telegram.bot-token=" + MiniAppControllerTest.TOKEN, "telegram.polling=false",
        "telegram.miniapp.photos-per-hour=4"})
@AutoConfigureMockMvc
class MiniAppControllerTest {

    static final String TOKEN = "123456:test-token";

    @Autowired
    private MockMvc mvc;

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

    @Test
    void configSaysEnabled() throws Exception {
        mvc.perform(get("/api/v1/miniapp/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.max_photos").value(10));
    }

    @Test
    void requestWithoutTelegramSignatureIs401() throws Exception {
        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg())
                        .header("X-Telegram-Init-Data", initData(1).replace("Test", "Evil")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedRequestGetsPhotosAndSummaryWithPrice() throws Exception {
        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg()).file(jpeg())
                        .header("X-Telegram-Init-Data", initData(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(2))
                .andExpect(jsonPath("$.photos[0].damages[0].bounding_box.length()").value(4))
                .andExpect(jsonPath("$.summary.damages.length()").value(1))
                .andExpect(jsonPath("$.summary.total.currency").value("EUR"));
    }

    @Test
    void limitPerUserIsEnforced() throws Exception {
        String user = initData(3);
        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg()).file(jpeg()).file(jpeg())
                        .header("X-Telegram-Init-Data", user))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/miniapp/analyze").file(jpeg()).file(jpeg())
                        .header("X-Telegram-Init-Data", user))
                .andExpect(status().isTooManyRequests());
    }
}
