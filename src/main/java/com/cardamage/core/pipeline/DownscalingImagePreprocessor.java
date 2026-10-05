package com.cardamage.core.pipeline;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Scales an image down so that its longer side is at most maxSide pixels
 * and re-encodes it as JPEG, if the image is larger than that or larger
 * than maxBytes. Smaller images are passed through unchanged.
 *
 * Why: the API accepts images up to 5 MB, and large images are downscaled
 * by the API anyway; doing it here keeps uploads small and predictable.
 *
 * Note: EXIF orientation is not applied (javax.imageio ignores it).
 * convert_syndcar.py reports whether any dataset image carries an EXIF
 * rotation; if none do, this has no effect on the benchmark.
 */
public class DownscalingImagePreprocessor implements ImagePreprocessor {

    private final int maxSide;
    private final long maxBytes;
    private final float jpegQuality;

    public DownscalingImagePreprocessor(int maxSide, long maxBytes, float jpegQuality) {
        this.maxSide = maxSide;
        this.maxBytes = maxBytes;
        this.jpegQuality = jpegQuality;
    }

    @Override
    public PreparedImage prepare(byte[] bytes, String mediaType) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("image could not be read: " + e.getMessage(), e);
        }
        if (image == null) {
            throw new IllegalArgumentException("image could not be read (not a valid JPEG or PNG)");
        }

        int longer = Math.max(image.getWidth(), image.getHeight());
        if (longer <= maxSide && bytes.length <= maxBytes) {
            return new PreparedImage(bytes, mediaType);
        }

        double scale = Math.min(1.0, (double) maxSide / longer);
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, width, height, java.awt.Color.WHITE, null);
        g.dispose();

        return new PreparedImage(encodeJpeg(scaled), "image/jpeg");
    }

    private byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(jpegQuality);
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
}
