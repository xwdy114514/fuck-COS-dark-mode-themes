import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class DarkenPng2 {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                int alpha = (p >>> 24) & 0xff;
                int r = (int) (((p >> 16) & 0xff) * 0.5);
                int g = (int) (((p >> 8) & 0xff) * 0.5);
                int b = (int) ((p & 0xff) * 0.5);
                dst.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(dst, "png", new File(a[1]));
        System.out.println("ok " + w + "x" + h);
    }
}
