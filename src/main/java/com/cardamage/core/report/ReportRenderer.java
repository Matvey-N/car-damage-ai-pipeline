package com.cardamage.core.report;

import com.cardamage.core.demo.PriceEstimator;
import com.cardamage.core.model.Damage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws the inspection report as A4 pages (150 dpi) and packs them into a PDF.
 * Page 1: header, (for a return inspection) the new damages, the summary list
 * with the demo price range, the disclaimer. Then every photo with its numbered
 * boxes and its own list.
 */
public class ReportRenderer {

    public static final int W = 1240;
    public static final int H = 1754;
    private static final int M = 90;

    private static final Color INK = new Color(0x16, 0x20, 0x2c);
    private static final Color SOFT = new Color(0x5a, 0x64, 0x70);
    private static final Color PAPER = new Color(0xfb, 0xfa, 0xf7);
    private static final Color BAND = new Color(0x14, 0x21, 0x3d);
    private static final Color NEW = new Color(0xc0, 0x39, 0x2b);
    private static final Map<String, Color> TYPE_COLOR = Map.of(
            "glass_shatter", new Color(0x2f, 0x8f, 0xde), "lamp_broken", new Color(0xe0, 0x8a, 0x1e),
            "crack", new Color(0xb2, 0x4b, 0xd6), "scratch", new Color(0x2f, 0xae, 0x6a));
    private static final Map<String, String> TYPE = Map.of(
            "glass_shatter", "Разбитое стекло", "lamp_broken", "Разбитая фара/фонарь",
            "crack", "Трещина", "scratch", "Царапина");
    private static final Map<String, String> PART = Map.of(
            "bumper", "бампер/панель", "door", "дверь", "light", "фара/фонарь", "window", "боковое стекло",
            "windshield", "лобовое/заднее стекло", "hood", "капот", "fender", "крыло", "mirror", "зеркало",
            "wheel", "колесо", "other", "другое");
    private static final Map<String, String> SEV = Map.of("minor", "лёгкое", "moderate", "среднее", "severe", "тяжёлое");
    private static final Map<String, String> ACT = Map.of("repair", "ремонт", "replacement", "замена");

    /** One photo in the report. newFlags: null, or per damage whether it is new (return inspection). */
    public record PhotoPart(String title, byte[] image, List<Damage> damages, List<Boolean> newFlags) {
    }

    /** newDamages / newTotal: null unless this is a return inspection compared with a pickup one. */
    public record Report(String title, String subtitle, List<Damage> summary, List<PriceEstimator.Range> prices,
                         PriceEstimator.Range total, String currency, Integer newDamages,
                         PriceEstimator.Range newTotal, List<PhotoPart> photos) {
    }

    private final Font regular;
    private final Font bold;

    public ReportRenderer() {
        this.regular = load("/fonts/DejaVuSans.ttf");
        this.bold = load("/fonts/DejaVuSans-Bold.ttf");
    }

    private static Font load(String resource) {
        try (InputStream in = ReportRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("font not found: " + resource);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load font " + resource, e);
        }
    }

    public byte[] pdf(Report report) {
        List<ImagePdf.Page> pages = new ArrayList<>();
        for (BufferedImage page : render(report)) {
            pages.add(new ImagePdf.Page(jpeg(page), page.getWidth(), page.getHeight()));
        }
        return ImagePdf.write(pages);
    }

    public List<BufferedImage> render(Report r) {
        Pages pages = new Pages();
        Graphics2D g = pages.g;

        // header band
        g.setColor(BAND);
        g.fillRect(0, 0, W, 230);
        g.setColor(PAPER);
        g.setFont(bold.deriveFont(52f));
        g.drawString(r.title(), M, 120);
        g.setFont(regular.deriveFont(28f));
        g.setColor(new Color(0xc9, 0xd1, 0xe0));
        g.drawString(r.subtitle(), M, 175);
        pages.y = 300;

        if (r.newDamages() != null) {
            pages.need(150);
            g = pages.g;
            g.setColor(r.newDamages() > 0 ? NEW : new Color(0x2f, 0x8a, 0x55));
            g.fillRoundRect(M, pages.y, W - 2 * M, 130, 24, 24);
            g.setColor(Color.WHITE);
            g.setFont(bold.deriveFont(40f));
            g.drawString(r.newDamages() > 0 ? "Новые повреждения: " + r.newDamages() : "Новых повреждений не найдено",
                    M + 36, pages.y + 62);
            if (r.newDamages() > 0 && r.newTotal() != null) {
                g.setFont(regular.deriveFont(28f));
                g.drawString("ориентир стоимости новых: " + r.newTotal().min() + "–" + r.newTotal().max() + " "
                        + r.currency(), M + 36, pages.y + 106);
            }
            pages.y += 220;
        }

        g.setColor(INK);
        g.setFont(bold.deriveFont(36f));
        g.drawString("Итог по всем фото", M, pages.y);
        pages.y += 60;
        if (r.summary().isEmpty()) {
            g.setFont(regular.deriveFont(30f));
            g.drawString("Видимых повреждений не найдено.", M, pages.y);
            pages.y += 50;
        } else {
            g.setFont(regular.deriveFont(28f));
            g.setColor(SOFT);
            g.drawString("Ориентир стоимости", M, pages.y);
            g.setColor(INK);
            g.setFont(bold.deriveFont(56f));
            g.drawString(r.total().min() + "–" + r.total().max() + " " + r.currency(), M, pages.y + 66);
            pages.y += 120;
            for (int i = 0; i < r.summary().size(); i++) {
                PriceEstimator.Range p = i < r.prices().size() ? r.prices().get(i) : null;
                damageRow(pages, i + 1, r.summary().get(i), p, false);
            }
        }
        pages.y += 20;
        pages.need(120);
        g = pages.g;
        g.setColor(SOFT);
        g.setFont(regular.deriveFont(24f));
        pages.y = paragraph(g, "Это демонстрация исследовательского прототипа, а не смета. Цены — условный справочник. "
                + "Модель пропускает часть мелких повреждений; результат можно исправить в приложении.", M, pages.y,
                W - 2 * M, 34);

        for (PhotoPart photo : r.photos()) {
            photo(pages, photo);
        }
        pages.finish();
        return pages.done;
    }

    private void damageRow(Pages pages, int n, Damage d, PriceEstimator.Range price, boolean isNew) {
        pages.need(90);
        Graphics2D g = pages.g;
        int y = pages.y;
        Color c = TYPE_COLOR.getOrDefault(d.getDamageType(), SOFT);
        g.setColor(c);
        g.fillOval(M, y - 30, 44, 44);
        g.setColor(Color.WHITE);
        g.setFont(bold.deriveFont(24f));
        centre(g, String.valueOf(n), M + 22, y);
        g.setColor(INK);
        g.setFont(bold.deriveFont(30f));
        String head = TYPE.getOrDefault(d.getDamageType(), d.getDamageType()) + " — "
                + PART.getOrDefault(d.getPart(), d.getPart());
        g.drawString(head, M + 64, y);
        if (isNew) {
            int x = M + 64 + g.getFontMetrics().stringWidth(head) + 16;
            g.setColor(NEW);
            g.fillRoundRect(x, y - 28, 120, 36, 16, 16);
            g.setColor(Color.WHITE);
            g.setFont(bold.deriveFont(22f));
            centre(g, "НОВОЕ", x + 60, y - 2);
        }
        g.setColor(SOFT);
        g.setFont(regular.deriveFont(24f));
        String details = SEV.getOrDefault(d.getSeverity(), d.getSeverity()) + " · "
                + ACT.getOrDefault(d.getAction(), d.getAction())
                + (d.getConfidence() == null ? "" : " · уверенность " + Math.round(d.getConfidence() * 100) + "%")
                + (price == null ? "" : " · " + price.min() + "–" + price.max() + " EUR");
        g.drawString(details, M + 64, y + 36);
        pages.y += 84;
    }

    private void photo(Pages pages, PhotoPart p) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(p.image()));
        } catch (IOException e) {
            img = null;
        }
        int maxW = W - 2 * M;
        int maxH = 900;
        int w = maxW;
        int h = img == null ? 200 : (int) Math.round((double) img.getHeight() / img.getWidth() * w);
        if (h > maxH) {
            w = (int) Math.round((double) w * maxH / h);
            h = maxH;
        }
        pages.need(80 + h + 40);
        Graphics2D g = pages.g;
        g.setColor(INK);
        g.setFont(bold.deriveFont(34f));
        g.drawString(p.title(), M, pages.y);
        pages.y += 24;
        int x0 = M;
        int y0 = pages.y;
        if (img == null) {
            g.setColor(SOFT);
            g.drawRect(x0, y0, w, h);
            g.setFont(regular.deriveFont(24f));
            g.drawString("фото недоступно", x0 + 20, y0 + 50);
        } else {
            g.drawImage(img, x0, y0, w, h, null);
            float labelSize = Math.max(26f, w / 34f);
            for (int i = 0; i < p.damages().size(); i++) {
                Damage d = p.damages().get(i);
                List<Double> b = d.getBoundingBox();
                if (b == null || b.size() != 4) {
                    continue;
                }
                boolean isNew = p.newFlags() != null && i < p.newFlags().size() && p.newFlags().get(i);
                Color c = isNew ? NEW : TYPE_COLOR.getOrDefault(d.getDamageType(), Color.YELLOW);
                int bx = x0 + (int) Math.round(b.get(0) * w);
                int by = y0 + (int) Math.round(b.get(1) * h);
                int bw = (int) Math.round(b.get(2) * w);
                int bh = (int) Math.round(b.get(3) * h);
                g.setStroke(new BasicStroke(isNew ? 6f : 4f));
                g.setColor(c);
                g.drawRect(bx, by, bw, bh);
                g.setFont(bold.deriveFont(labelSize));
                String label = String.valueOf(i + 1);
                int ly = Math.max(y0 + (int) labelSize, by - 6);
                g.setColor(Color.BLACK);
                g.drawString(label, bx + 3, ly + 2);
                g.setColor(c);
                g.drawString(label, bx + 1, ly);
            }
            g.setStroke(new BasicStroke(1f));
        }
        pages.y = y0 + h + 70;
        if (p.damages().isEmpty()) {
            pages.need(50);
            pages.g.setColor(SOFT);
            pages.g.setFont(regular.deriveFont(26f));
            pages.g.drawString("На этом фото повреждений не найдено.", M, pages.y);
            pages.y += 60;
        }
        for (int i = 0; i < p.damages().size(); i++) {
            boolean isNew = p.newFlags() != null && i < p.newFlags().size() && p.newFlags().get(i);
            damageRow(pages, i + 1, p.damages().get(i), null, isNew);
        }
        pages.y += 30;
    }

    private static void centre(Graphics2D g, String s, int cx, int baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, baseline);
    }

    private static int paragraph(Graphics2D g, String text, int x, int y, int width, int lineHeight) {
        FontMetrics fm = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) > width && line.length() > 0) {
                g.drawString(line.toString(), x, y);
                y += lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            g.drawString(line.toString(), x, y);
            y += lineHeight;
        }
        return y;
    }

    private static byte[] jpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.85f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Page flow: starts a new page when the next block does not fit. */
    private static final class Pages {
        final List<BufferedImage> done = new ArrayList<>();
        BufferedImage page;
        Graphics2D g;
        int y;

        Pages() {
            newPage();
        }

        void newPage() {
            if (g != null) {
                g.dispose();
                done.add(page);
            }
            page = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            g = page.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(PAPER);
            g.fillRect(0, 0, W, H);
            y = M + 40;
        }

        void need(int height) {
            if (y + height > H - M) {
                newPage();
            }
        }

        void finish() {
            if (g != null) {
                g.dispose();
                done.add(page);
                g = null;
            }
        }
    }
}
