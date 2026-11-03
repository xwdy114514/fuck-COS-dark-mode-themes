import java.io.*;
import java.util.Enumeration;
import java.util.zip.*;

/** 重新打包 APK：resources.arsc 保持 STORED，并用 0xD935 额外字段对齐到 4 字节。 */
public class Align {
    static class Counter extends FilterOutputStream {
        long pos;
        Counter(OutputStream o) { super(o); }
        public void write(int b) throws IOException { out.write(b); pos++; }
        public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); pos += len; }
    }

    public static void main(String[] a) throws Exception {
        File in = new File(a[0]);
        File out = new File(a[1]);
        ZipFile zf = new ZipFile(in);
        Counter c = new Counter(new BufferedOutputStream(new FileOutputStream(out)));
        ZipOutputStream zo = new ZipOutputStream(c);
        byte[] buf = new byte[65536];
        Enumeration<? extends ZipEntry> es = zf.entries();
        while (es.hasMoreElements()) {
            ZipEntry e = es.nextElement();
            String name = e.getName();
            byte[] nameBytes = name.getBytes("UTF-8");
            boolean store = "resources.arsc".equals(name) || name.endsWith(".so");
            ZipEntry n = new ZipEntry(name);
            n.setTime(e.getTime());
            if (store) {
                n.setMethod(ZipEntry.STORED);
                n.setSize(e.getSize());
                n.setCompressedSize(e.getSize());
                n.setCrc(e.getCrc());
                int base = 30 + nameBytes.length;
                int extraLen = (4 - ((int) ((c.pos + base) % 4))) % 4;
                if (extraLen > 0 && extraLen < 4) extraLen += 4;
                if (extraLen > 0) {
                    byte[] x = new byte[extraLen];
                    x[0] = 0x35;
                    x[1] = (byte) 0xD9;
                    int sz = extraLen - 4;
                    x[2] = (byte) (sz & 0xFF);
                    x[3] = (byte) ((sz >> 8) & 0xFF);
                        n.setExtra(x);
                    System.out.println(name + " data_off_mod4=" + ((c.pos + base + extraLen) % 4));
                    System.out.println(name + " data_off_mod4=" + ((c.pos + base + extraLen) % 4));
                }
            } else {
                n.setMethod(ZipEntry.DEFLATED);
            }
            zo.putNextEntry(n);
            InputStream is = zf.getInputStream(e);
            int r;
            while ((r = is.read(buf)) > 0) zo.write(buf, 0, r);
            is.close();
            zo.closeEntry();
        }
        zo.close();
        zf.close();
        System.out.println("aligned: " + out.length() + " bytes");
    }
}
