import tool.compet.util.DkFiles;
import tool.compet.util.DkLogs;
import tool.compet.util.DkStrings;
import tool.compet.util.DkUtils;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// java GenerateDatabaseModels
public class GenerateDatabaseModels {
   private static final String ls = "\n";
   private static final String ls2 = ls + ls;
   private static final String fs = File.separator;

   private final String rootPath;
   private final String migrationDirPath;
   private final String modelDirPath;
   private final String daoDirPath;

   private GenerateDatabaseModels(String[] args) {
      rootPath = new File("").getAbsolutePath();
      migrationDirPath = rootPath + fs + "database" + fs + "migrations";
      modelDirPath = rootPath + fs + "app" + fs + "Persistence" + fs + "Database" + fs + "Model";
      daoDirPath = rootPath + fs + "app" + fs + "Persistence" + fs + "Database" + fs + "Dao";
   }

   private void start() throws Exception {
      File modelDir = new File(modelDirPath);

      DkFiles.createNewDirRecursively(modelDir);
      DkFiles.createNewDirRecursively(new File(daoDirPath));

      // Create new if not exist, format source code...
      prepareModels(modelDir);

      // Run ide-helper:models to detect then write phpDocuments for all models which extends Model class
      List<String> result = new ArrayList<>();
      DkUtils.execCommand("php artisan ide-helper:models --write", result);

      for (String line : result) {
         DkLogs.println(line);
      }

      // Cleanup and Generate fields and Dao class for each model
      for (File modelFile : Objects.requireNonNull(modelDir.listFiles())) {
         if (modelFile.isFile()) {
            generateModelFields(modelFile);
            generateDaoClassIfNotExist(DkFiles.calcFileNameWithoutExtension(modelFile));
         }
      }
   }

   private void prepareModels(File outDir) throws Exception {
      File inDir = new File(migrationDirPath);

      if (!inDir.exists() || !inDir.isDirectory()) {
         DkLogs.complain(this, "Aborted since not exist migration folder [%s]", getRelativePath(inDir));
      }

      for (File scriptFile : Objects.requireNonNull(inDir.listFiles())) {
         String fname = scriptFile.getName();

         int startIndex = fname.lastIndexOf("_create_");
         int endIndex = fname.indexOf("_table.php");

         if (startIndex >= 0) {
            startIndex += "_create_".length();

            if (startIndex < endIndex) {
               String tableName = fname.substring(startIndex, endIndex);
               String modelFileName = fname.substring(startIndex - 1, endIndex);

               // Make class name since now it is like `_transaction_detail`
               StringBuilder sb = new StringBuilder(modelFileName);

               for (int i = 0, C = sb.length(); i < C; ++i) {
                  if (sb.charAt(i) == '_') {
                     sb.setCharAt(i + 1, Character.toUpperCase(sb.charAt(i + 1)));
                  }
               }

               final String modelClassName = sb.toString().replaceAll("_", "");
               modelFileName = modelClassName + ".php";

               // Generate model file
               File outModelFile = new File(modelDirPath + fs + modelFileName);

               // remove all comments for this model to cause ide-helper generate new document
               if (outModelFile.exists()) {
                  BufferedReader reader = DkFiles.newUtf8Reader(outModelFile);
                  String readline;
                  ArrayList<String> lines = new ArrayList<>();

                  while ((readline = reader.readLine()) != null) {
                     String trimed = readline.trim();

                     if (trimed.startsWith("/*") || trimed.startsWith("*")) {
                        continue;
                     }

                     lines.add(readline);
                  }
                  reader.close();

                  // Write model
                  BufferedWriter writer = DkFiles.newUtf8Writer(outModelFile);
                  format(lines);

                  for (String line : lines) {
                     writer.write(line);
                     writer.write(ls);
                  }

                  writer.close();
               }
               // generate new model class so ide-helper can add PhpDoc for us
               else {
                  if (!outModelFile.createNewFile()) {
                     DkLogs.complain(this, "Could not create file [%s]", getRelativePath(outModelFile));
                  }

                  BufferedWriter bw = DkFiles.newUtf8Writer(outModelFile);
                  bw.write("<?php");
                  bw.write(ls2);
                  bw.write("namespace App\\Persistence\\Database\\Model;");
                  bw.write(ls2);
                  bw.write("use Illuminate\\Database\\Eloquent\\Model;");
                  bw.write(ls2);
                  bw.write(DkStrings.format("class %s extends Model {", modelClassName));
                  bw.write(ls);
                  bw.write(DkStrings.format("   public $table = '%s';", tableName));
                  bw.write(ls);
                  bw.write("   public $timestamps = true;");
                  bw.write(ls);
                  bw.write("}");
                  bw.write(ls);
                  bw.close();

                  DkLogs.println("Generated new model [%s]", getRelativePath(outModelFile));
               }
            }
         }
      }
   }

   private void generateModelFields(File modelFile) throws Exception {
      // Collect fields from properties
      ArrayList<String> fieldDeclarations = new ArrayList<>();
      List<String> lines = DkFiles.readFileAsUtf8Lines(modelFile);
      String target = "@property ";

      for (String line : lines) {
         if (line.contains(target)) {
            int startIndex = line.indexOf(target) + target.length();

            for (int index = startIndex, N = line.length(); index < N; ++index) {
               char ch = line.charAt(index);

               if (ch == '$') {
                  startIndex = index + 1;
                  int endIndex = line.indexOf(" ", startIndex);

                  if (endIndex < 0) {
                     endIndex = N;
                  }

                  if (endIndex > startIndex) {
                     String fieldName = line.substring(startIndex, endIndex).trim();
                     String fieldComment = line.substring(endIndex).trim();

                     if (fieldName.length() > 0) {
                        fieldDeclarations.add(DkStrings.format("   /** %s */", fieldComment));
                        fieldDeclarations.add(DkStrings.format("   public $c_%s = '%s';", fieldName, fieldName));
                     }
                  }

                  break;
               }
            }
         }
      }

      // Remove generated old fields
      for (int index = lines.size() - 1; index >= 0; --index) {
         String line = lines.get(index);

         if (line.trim().startsWith("public $c_")) {
            lines.remove(index);
         }
      }

      // Add newly fields
      for (int index = lines.size() - 1; index >= 0; --index) {
         if (lines.get(index).endsWith("}")) {
            if (fieldDeclarations.size() > 0) {
               fieldDeclarations.add(0, "");
               lines.addAll(index, fieldDeclarations);
            }
            break;
         }
      }

      // Write to file
      BufferedWriter bw = DkFiles.newUtf8Writer(modelFile);
      format(lines);

      for (String line : lines) {
         bw.write(line);
         bw.write(ls);
      }
      bw.close();

      DkLogs.println("Generated %d fields in [%s]",
         Math.max(0, fieldDeclarations.size() - 1), getRelativePath(modelFile));
   }

   private void generateDaoClassIfNotExist(String modelClassName) throws Exception {
      String daoClassName = modelClassName + "Dao";
      File daoFile = new File(daoDirPath, daoClassName + ".php");

      if (daoFile.exists()) {
         return;
      }

      DkFiles.createNewFileRecursively(daoFile);

      BufferedWriter bw = DkFiles.newUtf8Writer(daoFile);
      bw.write("<?php");
      bw.write(ls2);
      bw.write("namespace App\\Persistence\\Database\\Dao;");
      bw.write(ls2);
      bw.write(DkStrings.format("use App\\Persistence\\Database\\Model\\%s;", modelClassName));
      bw.write(ls2);
      bw.write(DkStrings.format("class %s extends %s {", daoClassName, modelClassName));
      bw.write(ls);
      bw.write("}");
      bw.write(ls);
      bw.close();

      DkLogs.println("Generated new file [%s]", getRelativePath(daoFile));
   }

   private void format(List<String> lines) {
      // remove double linefeed
      boolean isPrevEmpty = true;

      for (int index = lines.size() - 1; index >= 0; --index) {
         String line = lines.get(index);
         boolean isEmpty = line.trim().length() == 0;

         if (isEmpty && isPrevEmpty) {
            lines.remove(index);
         }

         isPrevEmpty = isEmpty;
      }
   }

   protected String getRelativePath(File f) {
      return f.getPath().substring(rootPath.length() + 1);
   }

   public static void main(String... args) {
      try {
         new GenerateDatabaseModels(args).start();
      }
      catch (Exception e) {
         e.printStackTrace();
         JOptionPane.showConfirmDialog(null, e.getMessage(), "error", JOptionPane.YES_NO_CANCEL_OPTION);
      }
   }
}
