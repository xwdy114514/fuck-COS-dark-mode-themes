import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class TopCheck {
    public static void main(String[] a) throws Exception {
        for (String p : a) {
            BufferedImage i = ImageIO.read(new File(p));
            int w = i.getWidth(), h = i.getHeight();
            StringBuilder sb = new StringBuilder(p + " " + w + "x" + h + " : ");
            for (int y : new int[]{0, 10, 50, 100, 200, 300, 400, 500}) {
                if (y < h) sb.append("y").append(y).append("=").append(Integer.toHexString(i.getRGB(w / 2, y))).append(" ");
            }
            System.out.println(sb);
        }
    }
}
