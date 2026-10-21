import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.CRC32;

public class DarkenPng3 {
    public static void main(String[] a) throws Exception {
        byte[] orig = read(new File(a[0]));
        byte[] npTc = extractChunk(orig, "npTc");

        BufferedImage src = ImageIO.read(new File(a[0]));
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                int al = (p >>> 24) & 0xff;
                int r = (int) (((p >> 16) & 0xff) * 0.5);
                int g = (int) (((p >> 8) & 0xff) * 0.5);
                int b = (int) ((p & 0xff) * 0.5);
                dst.setRGB(x, y, (al << 24) | (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream tmp = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", tmp);
        byte[] out = tmp.toByteArray();
        if (npTc != null) out = insertAfterIhdr(out, "npTc", npTc);
        write(new File(a[1]), out);
        System.out.println("ok " + w + "x" + h + " npTc=" + (npTc == null ? 0 : npTc.length));
    }

    static byte[] extractChunk(byte[] d, String type) {
        int p = 8;
        while (p + 12 <= d.length) {
            int len = be32(d, p); p += 4;
            String t = new String(d, p, 4); p += 4;
            if (t.equals(type)) {
                byte[] out = new byte[len];
                System.arraycopy(d, p, out, 0, len);
                return out;
            }
            p += len + 4;
        }
        return null;
    }

    static byte[] insertAfterIhdr(byte[] d, String type, byte[] data) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(d, 0, 8);
        int p = 8;
        boolean inserted = false;
        while (p + 12 <= d.length) {
            int len = be32(d, p);
            int total = 4 + 4 + len + 4;
            String t = new String(d, p + 4, 4);
            o.write(d, p, total);
            if (!inserted && t.equals("IHDR")) {
                writeChunk(o, type, data);
                inserted = true;
            }
            p += total;
        }
        return o.toByteArray();
    }

    static void writeChunk(ByteArrayOutputStream o, String type, byte[] data) throws Exception {
        o.write(be32b(data.length));
        byte[] t = type.getBytes("US-ASCII");
        o.write(t);
        o.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        o.write(be32b((int) crc.getValue()));
    }

    static byte[] be32b(int v) { return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v}; }

    static int be32(byte[] d, int p) {
        return ((d[p] & 0xff) << 24) | ((d[p + 1] & 0xff) << 16) | ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
    }

    static byte[] read(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        DataInputStream in = new DataInputStream(new FileInputStream(f));
        in.readFully(b); in.close(); return b;
    }

    static void write(File f, byte[] b) throws IOException {
        DataOutputStream out = new DataOutputStream(new FileOutputStream(f));
        out.write(b); out.close();
    }
}
