import tool.compet.javacore.constant.DkConstant;
import tool.compet.javacore.log.DkConsoleLogs;
import tool.compet.javacore.util.DkFiles;
import tool.compet.javacore.util.DkStrings;
import tool.compet.javacore.util.DkUtils;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.util.*;

// java -jar laravel_database_generator.jar modelNameSpace:app/Persistence/Database/Model daoNameSpace:app/Persistence/Database/Dao schemaNameSpace:app/Common/DbSchema
public class GenerateDatabaseModels {
   private static final String ls = "\n";
   private static final String ls2 = ls + ls;
   private static final String fs = File.separator;

   private final String rootPath;
   private final String migrationDirPath;

   private String modelDirPath;
   private String modelNameSpace;

   private String daoDirPath;
   private String daoNameSpace;

   private String schemaDirPath;
   private String schemaNameSpace;

   private GenerateDatabaseModels() {
      rootPath = DkConstant.ABS_PATH;
      migrationDirPath = DkFiles.makePath(rootPath, "database", "migrations");
   }

   private void start(Map<String, String> pairs) throws Exception {
      modelNameSpace = pairs.getOrDefault("modelNameSpace", "App/Persistence/Database/Model");
      daoNameSpace = pairs.getOrDefault("daoNameSpace", "App/Persistence/Database/Dao");
      schemaNameSpace = pairs.getOrDefault("schemaNameSpace", "App/Common/DbSchema");

      String fileSeparator = "[/\\\\]+";
      String defaultFileSeparator = "\\\\";

      modelDirPath = rootPath + fs + DkStrings.join(fs, ('a' + modelNameSpace.substring(1)).split(fileSeparator));
      daoDirPath = rootPath + fs + DkStrings.join(fs, ('a' + daoNameSpace.substring(1)).split(fileSeparator));
      schemaDirPath = rootPath + fs + DkStrings.join(fs, ('a' + schemaNameSpace.substring(1)).split(fileSeparator));

      modelNameSpace = modelNameSpace.replaceAll(fileSeparator, defaultFileSeparator);
      daoNameSpace = daoNameSpace.replaceAll(fileSeparator, defaultFileSeparator);
      schemaNameSpace = schemaNameSpace.replaceAll(fileSeparator, defaultFileSeparator);

      File modelDir = new File(modelDirPath);

      // Create new directory if not exist
      DkFiles.createNewDirRecursively(modelDir);
      DkFiles.createNewDirRecursively(new File(daoDirPath));
      DkFiles.createNewDirRecursively(new File(schemaDirPath));

      // Create new if not exist, format source code...
      prepareModels();

      // Run ide-helper:models to detect then write phpDocuments for all models which extends Model class
      List<String> result = new ArrayList<>();
      DkUtils.execCommand("php artisan ide-helper:models --write", result);

      for (String line : result) {
         DkConsoleLogs.justLog(line);
      }

      // Collect properties from each model file
      // Create new Dao class for each model if not exist
      // Generate schema for each model if not exist
      for (File modelFile : DkFiles.collectFilesRecursively(modelDir)) {
         String modelClassName = DkFiles.calcFileNameWithoutExtension(modelFile);
         Map<String, String> field2comment = collectPropertiesFromModelFile(modelFile);
         generateDaoClassIfNotExist(modelClassName);
         generateSchemaIfNotExist(modelClassName, field2comment);
      }
   }

   private void prepareModels() throws Exception {
      File inDir = new File(migrationDirPath);

      if (!inDir.exists() || !inDir.isDirectory()) {
         DkUtils.complain(this, "Aborted since not exist migration folder [%s]", getRelativePath(inDir));
      }

      for (File migrationFile : Objects.requireNonNull(inDir.listFiles())) {
         String fname = migrationFile.getName();

         int startIndex = fname.lastIndexOf("create_");
         int endIndex = fname.indexOf("_table.php");

         if (startIndex < 0) {
            continue;
         }
         if (endIndex < 0) {
            endIndex = fname.indexOf('.');
         }

         startIndex += "_create_".length();

         if (startIndex >= endIndex) {
            continue;
         }

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
               DkUtils.complain(this, "Could not create file [%s]", getRelativePath(outModelFile));
            }

            BufferedWriter bw = DkFiles.newUtf8Writer(outModelFile);
            bw.write("<?php");
            bw.write(ls2);
            bw.write(DkStrings.format("namespace %s;", modelNameSpace));
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

            DkConsoleLogs.justLog("Generated new model [%s]", getRelativePath(outModelFile));
         }
      }
   }

   private Map<String, String> collectPropertiesFromModelFile(File modelFile) throws Exception {
      Map<String, String> field2comment = new LinkedHashMap<>();

      // Collect fields and its comments from properties
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
                        field2comment.put(fieldName, fieldComment);
                     }
                  }

                  break;
               }
            }
         }
      }

      return field2comment;
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
      bw.write(DkStrings.format("namespace %s;", daoNameSpace));
      bw.write(ls2);
      bw.write(DkStrings.format("use App\\Persistence\\Database\\Model\\%s;", modelClassName));
      bw.write(ls2);
      bw.write(DkStrings.format("class %s extends %s {", daoClassName, modelClassName));
      bw.write(ls);
      bw.write("}");
      bw.write(ls);
      bw.close();

      DkConsoleLogs.justLog("Generated new file [%s]", getRelativePath(daoFile));
   }

   private void generateSchemaIfNotExist(String modelClassName, Map<String, String> field2comment) throws Exception {
      String schemaClassName = modelClassName + "Schema";
      File schemaFile = new File(schemaDirPath, schemaClassName + ".php");

      if (schemaFile.exists()) {
         return;
      }

      DkFiles.createNewFileRecursively(schemaFile);

      BufferedWriter bw = DkFiles.newUtf8Writer(schemaFile);
      bw.write("<?php");
      bw.write(ls2);
      bw.write(DkStrings.format("namespace %s;", schemaNameSpace));
      bw.write(ls2);
      bw.write(DkStrings.format("class %s {", schemaClassName));
      bw.write(ls);

      for (String fieldName : field2comment.keySet()) {
         bw.write(DkStrings.format("   /** %s */", field2comment.get(fieldName)));
         bw.write(ls);
         bw.write(DkStrings.format("   public $%s = '%s';", fieldName, fieldName));
         bw.write(ls);
      }

      bw.write("}");
      bw.write(ls);
      bw.close();

      DkConsoleLogs.justLog("Generated new file [%s]", getRelativePath(schemaFile));
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
         new GenerateDatabaseModels().start(ArgParser.parseArgs(args));
      }
      catch (Exception e) {
         e.printStackTrace();
         JOptionPane.showConfirmDialog(null, e.getMessage(), "Error", JOptionPane.YES_NO_CANCEL_OPTION);
      }
   }
}
