package com.synapsemcp.ingestion.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Deliberately a real, not-mocked test - {@link RapidOcrService} wraps a real, pure-JVM ONNX
 * runtime with no external credentials or network access needed at runtime (only at Maven
 * dependency-resolution time), so there's no reason to mock it the way every chat/embedding
 * provider call in this codebase must be. Verified live before adding the dependency at all (a
 * hand-rendered "Golden Retriever" PNG correctly OCR'd); this test is the permanent, automated
 * version of that same verification.
 */
class RapidOcrServiceTest {

    private final RapidOcrService service = new RapidOcrService();

    private static byte[] renderTextAsPng(String text) throws IOException {
        BufferedImage img = new BufferedImage(400, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 120);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 28));
        g.drawString(text, 20, 60);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void recognizesRealTextFromARealRenderedImage() throws IOException {
        byte[] png = renderTextAsPng("Golden Retriever");

        String result = service.runOcr(png);

        assertThat(result).containsIgnoringCase("Golden Retriever");
    }

    @Test
    void blankImageProducesNoTextWithoutThrowing() throws IOException {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 50, 50);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);

        String result = service.runOcr(out.toByteArray());

        assertThat(result).isBlank();
    }
}
