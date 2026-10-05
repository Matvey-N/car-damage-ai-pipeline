package com.cardamage.core.pipeline;

import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DownscalingImagePreprocessorTest {

    private final DownscalingImagePreprocessor pre = new DownscalingImagePreprocessor(1568, 4_500_000, 0.9f);

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x += 7) {
            for (int y = 0; y < height; y += 7) {
                img.setRGB(x, y, (x * 31 + y * 17) & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void smallImageIsPassedThroughUnchanged() throws Exception {
        byte[] original = png(800, 600);
        PreparedImage p = pre.prepare(original, "image/png");
        assertSame(original, p.bytes());
        assertEquals("image/png", p.mediaType());
    }

    @Test
    void largeImageIsScaledToMaxSideKeepingAspectRatio() throws Exception {
        PreparedImage p = pre.prepare(png(3000, 2000), "image/png");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(p.bytes()));
        assertEquals("image/jpeg", p.mediaType());
        assertEquals(1568, result.getWidth());
        assertEquals(1045, result.getHeight()); // 2000 * 1568 / 3000 = 1045.3
    }

    @Test
    void portraitImageIsScaledByItsLongerSide() throws Exception {
        PreparedImage p = pre.prepare(png(1000, 4000), "image/png");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(p.bytes()));
        assertEquals(1568, result.getHeight());
        assertEquals(392, result.getWidth());
    }

    @Test
    void heavySmallImageIsReencoded() throws Exception {
        DownscalingImagePreprocessor tight = new DownscalingImagePreprocessor(1568, 10, 0.9f);
        PreparedImage p = tight.prepare(png(400, 300), "image/png");
        assertEquals("image/jpeg", p.mediaType());
        assertEquals(400, ImageIO.read(new ByteArrayInputStream(p.bytes())).getWidth());
    }

    @Test
    void unreadableImageIsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> pre.prepare(new byte[]{1, 2, 3}, "image/jpeg"));
    }

    @Test
    void analyzerSendsThePreparedImage() throws Exception {
        StubVisionModelClient stub = StubVisionModelClient.withDefaultAnswer();
        byte[][] sent = new byte[1][];
        VisionModelClient recording = (image, mediaType, prompt) -> {
            sent[0] = image;
            return stub.analyze(image, mediaType, prompt);
        };
        SingleImageAnalyzer analyzer = new SingleImageAnalyzer(recording, new ResponseParser(new ObjectMapper()),
                new ResponseFormatValidator(), 3, 0, ms -> { }, pre);

        DamageAssessment result = analyzer.analyze(png(3000, 2000), "image/png");

        assertTrue(result.isSuccess());
        assertEquals(1568, ImageIO.read(new ByteArrayInputStream(sent[0])).getWidth());
    }

    @Test
    void unreadableImageIsNotSentToTheModel() {
        StubVisionModelClient stub = StubVisionModelClient.withDefaultAnswer();
        SingleImageAnalyzer analyzer = new SingleImageAnalyzer(stub, new ResponseParser(new ObjectMapper()),
                new ResponseFormatValidator(), 3, 0, ms -> { }, pre);
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(new byte[]{9, 9}, "image/jpeg"));
        assertEquals(0, stub.getCalls());
        assertEquals(List.of(), stub.getReceivedPrompts());
    }
}
