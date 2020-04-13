import com.ibm.icu.text.CharsetDetector;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * This converts charset of all files (and its sub folder) under /testdata from a charset to another charset.
 * <pre>{@code
 *    Input:
 *       - files: locate under directory `testdata/`, this dir is same level with `src/`.
 *       - fromCharset: source charset (define in `CharsetConverter.fromCharset`)
 *       - toCharset: destination charset (define in `CharsetConverter.toCharset`)
 *    Output:
 *       - files under testdata/
 * }</pre>
 *
 * Note: if you wanna run at Command Line Interface (CLI):
 * javac -classpath D:\darkcompet\contest\out\production\library_java;D:\darkcompet\contest\lib\gson-2.8.2.jar;D:\darkcompet\contest\lib\icu4j-charsetdetector-4_4_2.jar CharsetConverter.java
 * java -classpath D:\darkcompet\contest\out\production\library_java;D:\darkcompet\contest\lib\gson-2.8.2.jar;D:\darkcompet\contest\lib\icu4j-charsetdetector-4_4_2.jar CharsetConverter
 */
public class CharsetConverter {
   //TODO: Charset you think it is charset of your files, set `null` to tell program auto-detect.
   private Charset fromCharset = null;//Charset.forName("SHIFT_JIS");
   //TODO: Charset which program will convert to, if you set it to `null` then program will use `UTF-8` instead.
   private final Charset toCharset = StandardCharsets.UTF_8;

   public CharsetConverter() {
      String fs = File.separator;
      String inDirPath = new File("").getAbsolutePath() + fs + "testdata";
      File inDir = new File(inDirPath);
      DkFiles
   }

   /**
    * Detect charset and convert content of this file. Note that, this is approximate action, not 100% reliable.
    */
   private void loopOver(File file) {
      if (file.isFile()) {
         performConvertCharset(file);
      }
      else if (file.isDirectory()) {
         File[] fs = file.listFiles();

         if (fs != null) {
            for (File f : fs) {
               loopOver(f);
            }
         }
      }
   }

   private void performConvertCharset(File file) {
      Charset detectedCharset = detectCharset(file);

      if (detectedCharset == null) {
         println("[WARN] Skipped convert since could not detect charset for file %s", file.getPath());
      }
      else {
         if (toCharset.equals(detectedCharset)) {
            println("[Info] Skipped convert since same charset to convert for file %s", file.getName());
         } else {
            if (fromCharset != null && !detectedCharset.equals(fromCharset)) {
               println("[WARN] Detected charset [%s] is not matched with your provided toCharset [%s]",
                  detectedCharset.name(), fromCharset.name());
            }
            Charset fromCharset = (this.fromCharset == null) ? detectedCharset : this.fromCharset;

            if ("windows-1252".equalsIgnoreCase(fromCharset.name())) {
               fromCharset = Charset.forName("SHIFT_JIS");
            }
            startConvertCharset(file, fromCharset, toCharset);
         }
      }
   }

   private void startConvertCharset(File file, Charset fromCharset, Charset toCharset) {
      try {
         FileInputStream fis = new FileInputStream(file);
         byte[] content = fis.readAllBytes();
         fis.close();

         String input = new String(content, fromCharset);

         FileOutputStream fos = new FileOutputStream(file);
         fos.write(input.getBytes(toCharset));
         fos.close();

         println("[Info] Converted charset %s -> %s for file %s",
            fromCharset.name(), toCharset.name(), file.getPath());
      }
      catch (Exception e) {
         e.printStackTrace();
      }
   }

   private Charset detectCharset(File file) {
      try {
         BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
         CharsetDetector charsetDetector = new CharsetDetector();
         charsetDetector.setText(bis);
         String charsetName = charsetDetector.detect().getName();

         return charsetName == null ? null : Charset.forName(charsetName);
      }
      catch (Exception e) {
         e.printStackTrace();
         return null;
      }
   }

   public static void main(String[] args) {
      try {
         new CharsetConverter().start();
      }
      catch (Exception e) {
         e.printStackTrace();
      }
   }
}
