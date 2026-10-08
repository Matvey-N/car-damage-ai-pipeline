package com.cardamage.core.report;

import com.cardamage.core.demo.PriceEstimator;
import com.cardamage.core.model.Damage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportRendererTest {

    static byte[] photo(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x88, 0x99, 0xaa));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    static ReportRenderer.Report report(int photos) throws Exception {
        Damage scratch = new Damage("scratch", "door", "minor", "repair", 0.8, List.of(0.1, 0.2, 0.3, 0.2));
        Damage lamp = new Damage("lamp_broken", "light", "severe", "replacement", 0.6, List.of(0.6, 0.6, 0.2, 0.15));
        List<ReportRenderer.PhotoPart> parts = new ArrayList<>();
        for (int i = 0; i < photos; i++) {
            parts.add(new ReportRenderer.PhotoPart("Фото " + (i + 1) + " · Спереди", photo(800, 600),
                    List.of(scratch, lamp), List.of(false, true)));
        }
        return new ReportRenderer.Report("Отчёт об осмотре автомобиля", "8 октября 2026, 16:20 · при возврате",
                List.of(scratch, lamp), List.of(new PriceEstimator.Range(200, 450), new PriceEstimator.Range(150, 600)),
                new PriceEstimator.Range(350, 1050), "EUR", 1, new PriceEstimator.Range(150, 600), parts);
    }

    @Test
    void reportFlowsOntoSeveralA4Pages() throws Exception {
        List<BufferedImage> pages = new ReportRenderer().render(report(3));
        assertTrue(pages.size() >= 2, "three photos do not fit on page 1");
        assertEquals(ReportRenderer.W, pages.get(0).getWidth());
        assertEquals(ReportRenderer.H, pages.get(0).getHeight());
    }

    @Test
    void pdfHasOnePageObjectPerRenderedPage() throws Exception {
        ReportRenderer r = new ReportRenderer();
        byte[] pdf = r.pdf(report(3));
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.4"));
        assertTrue(text.trim().endsWith("%%EOF"));
        int pages = r.render(report(3)).size();
        assertTrue(text.contains("/Count " + pages));
    }
}
