import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class CheckPng {
    public static void main(String[] a) throws Exception {
        for (String p : a) {
            BufferedImage i = ImageIO.read(new File(p));
            if (i == null) { System.out.println(p + " -> null"); continue; }
            int w = i.getWidth(), h = i.getHeight();
            int c = i.getRGB(w / 2, h / 2);
            int c0 = i.getRGB(0, 0);
            System.out.println(p + " " + w + "x" + h + " center=" + Integer.toHexString(c) + " corner=" + Integer.toHexString(c0));
        }
    }
}
