package com.madsports.report;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ChartCanvasTest {

    static BufferedImage decode(String dataUri) throws IOException {
        assertThat(dataUri).startsWith("data:image/png;base64,");
        byte[] png = Base64.getDecoder().decode(dataUri.substring("data:image/png;base64,".length()));
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void rendersAtThreeTimesTheSizeOnWhite() throws IOException {
        BufferedImage img = decode(ChartCanvas.render(100, 40, g -> {
            g.setColor(ChartCanvas.WIN);
            g.fillRect(0, 0, 10, 10);
        }));
        assertThat(img.getWidth()).isEqualTo(300);
        assertThat(img.getHeight()).isEqualTo(120);
        assertThat(img.getRGB(5, 5) & 0xffffff).isEqualTo(ChartCanvas.WIN.getRGB() & 0xffffff);
        assertThat(img.getRGB(200, 100) & 0xffffff).isEqualTo(0xffffff);
    }

    @Test
    void shortNameForTheLegend() {
        assertThat(ChartCanvas.shortName("Simona Emma Demata Bianchi")).isEqualTo("Simona Bianchi");
        assertThat(ChartCanvas.shortName("Luciana Barilla")).isEqualTo("Luciana Barilla");
        assertThat(ChartCanvas.shortName("Maximiliano José Fernandezsantillan")).isEqualTo("Maximiliano Ferna…");
    }

    @Test
    void clockAndMax() {
        assertThat(ChartCanvas.clock(605)).isEqualTo("10:05");
        assertThat(ChartCanvas.clock(59)).isEqualTo("0:59");
        assertThat(ChartCanvas.max(new int[]{2, 7, 3})).isEqualTo(7);
        assertThat(ChartCanvas.max(new int[]{})).isZero();
    }
}
