package xwdy.deepseek.test;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
public class AppliedThemeRegression {
 static int count;
 static void check(boolean want,File dir,String label){boolean got=AppliedTheme.hasApplicationResources(dir);if(got!=want)throw new AssertionError(label+" "+got);count++;System.out.println("PASS "+label);}
 static void zip(File f)throws Exception{try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(f))){out.putNextEntry(new ZipEntry("res/drawable/example.xml"));out.write("resource".getBytes("UTF-8"));out.closeEntry();}}
 public static void main(String[] args)throws Exception {
  File dir=Files.createTempDirectory("mctga-active-test-").toFile();
  try {
   check(false,dir,"empty applied directory");
   Files.write(new File(dir,"config").toPath(),"{\"other\":[{\"filePath\":\"\"}]}".getBytes("UTF-8"));
   File pending=new File(dir,"applying");pending.mkdir();zip(new File(pending,"com.android.settings"));
   check(false,dir,"config and pending resources are not applied");
   zip(new File(dir,"downloaded.theme"));zip(new File(dir,"icons"));zip(new File(dir,"wallpaper"));
   File cache=new File(dir,"mctga-packs");cache.mkdir();zip(new File(cache,"com.android.settings"));
   check(false,dir,"downloads wallpaper and old caches cannot activate");
   File pack=new File(dir,"com.android.settings");pack.createNewFile();check(false,dir,"zero-byte pack rejected");
   Files.write(pack.toPath(),"not a zip".getBytes("UTF-8"));check(false,dir,"corrupt pack rejected");
   zip(pack);check(true,dir,"real applied application pack accepted");
   pack.delete();check(false,dir,"removal returns to inactive with caches intact");
   check(false,new File(dir,"missing"),"unreadable or absent state fails closed");
   System.out.println("APPLIED_THEME_ALL_PASS checks="+count);
  } finally { try(java.util.stream.Stream<Path> paths=Files.walk(dir.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});} }
 }
}
