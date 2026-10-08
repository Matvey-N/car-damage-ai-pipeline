package com.cardamage.core.report;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal PDF writer: every page is one JPEG image filling an A4 page.
 *
 * The report pages are drawn with Java2D (so Cyrillic text, photos and boxes
 * come out exactly as rendered) and then packed into a PDF. No PDF library
 * is needed. The text in such a PDF is not selectable; for a demo report
 * that is an acceptable trade-off.
 */
public final class ImagePdf {

    private static final double A4_WIDTH_PT = 595.28;
    private static final double A4_HEIGHT_PT = 841.89;

    public record Page(byte[] jpeg, int width, int height) {
    }

    private ImagePdf() {
    }

    public static byte[] write(List<Page> pages) {
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("a PDF needs at least one page");
        }
        Out out = new Out();
        out.ascii("%PDF-1.4\n%âãÏÓ\n");
        int n = pages.size();
        // object numbers: 1 catalog, 2 pages, then per page: page, content, image
        List<Integer> offsets = new ArrayList<>();
        offsets.add(out.size());
        out.ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets.add(out.size());
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            kids.append(3 + i * 3).append(" 0 R ");
        }
        out.ascii("2 0 obj\n<< /Type /Pages /Count " + n + " /Kids [" + kids + "] >>\nendobj\n");
        for (int i = 0; i < n; i++) {
            Page p = pages.get(i);
            int pageObj = 3 + i * 3;
            int contentObj = pageObj + 1;
            int imageObj = pageObj + 2;
            offsets.add(out.size());
            out.ascii(pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + A4_WIDTH_PT + " " + A4_HEIGHT_PT
                    + "] /Resources << /XObject << /Im0 " + imageObj + " 0 R >> >> /Contents " + contentObj
                    + " 0 R >>\nendobj\n");
            String content = "q " + A4_WIDTH_PT + " 0 0 " + A4_HEIGHT_PT + " 0 0 cm /Im0 Do Q\n";
            offsets.add(out.size());
            out.ascii(contentObj + " 0 obj\n<< /Length " + content.length() + " >>\nstream\n" + content
                    + "endstream\nendobj\n");
            offsets.add(out.size());
            out.ascii(imageObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + p.width() + " /Height "
                    + p.height() + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                    + p.jpeg().length + " >>\nstream\n");
            out.bytes(p.jpeg());
            out.ascii("\nendstream\nendobj\n");
        }
        int xref = out.size();
        out.ascii("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            out.ascii(String.format("%010d 00000 n \n", off));
        }
        out.ascii("trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static final class Out extends ByteArrayOutputStream {
        void ascii(String s) {
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            write(b, 0, b.length);
        }

        void bytes(byte[] b) {
            write(b, 0, b.length);
        }
    }
}
