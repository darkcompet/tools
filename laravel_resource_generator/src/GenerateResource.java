import tool.compet.util.DkFiles;
import tool.compet.util.DkLogs;
import tool.compet.util.DkStrings;

import java.io.BufferedWriter;
import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

// java GenerateResource
public class GenerateResource {
   private static final String ls = "\n";
   private static final String ls2 = ls + ls;
   private static final String fs = File.separator;

   private static final Pattern fieldKeyPattern = Pattern.compile("\\w+");

   // Patterns to detect key inside ()
   private static final Pattern keyPattern = Pattern.compile("['\"]\\w+\\.\\w+['\"]");
   private static final Pattern keyPattern_langOnly = Pattern.compile("Langs::\\w+\\.\\w+");
   private static final Pattern keyPattern_fullPath = Pattern.compile("\\\\?App\\\\Http\\\\Constants\\\\Langs::\\w+\\.\\w+");

   private static final Pattern validFullPathPattern = Pattern.compile("\\\\?App\\\\Http\\\\Constants\\\\Langs::\\w+(_\\w+)+");
   private static final Pattern validNonFullPathPattern = Pattern.compile("Langs::\\w+(_\\w+)+");

   // Regular expression for detect lang patterns in Views
   private static final Pattern[] patternForViews = new Pattern[] {
      Pattern.compile("\\{\\{__\\([^)]+\\)}}"),
      Pattern.compile("@lang\\([^)]+\\)"),
   };
   private static final String[] prefixForViews = {
      "{{__(",
      "@lang(",
   };
   private static final String[] suffixForViews = {
      ")}}",
      ")",
   };

   // Regular expression for detect pattern in Controllers
   private static final Pattern[] patternForCons = new Pattern[] {
      Pattern.compile("__\\([^)]+\\)"),
   };
   private static final String[] prefixForCons = {
      "__(",
   };
   private static final String[] suffixForCons = {
      ")",
   };

   private final String rootPath;
   private final String outDir;
   private final String lang;
   private final boolean levelLow;
   private final boolean levelMedium;
   private final boolean levelHigh;

   private GenerateResource(String[] args) {
      rootPath = new File("").getAbsolutePath();
      outDir = rootPath + fs + "app" + fs + "Http" + fs + "Constants";

      Map<String, String> validArgs = new HashMap<>();
      validArgs.put("lang", "vi");
      validArgs.put("level", "medium");

      if (args != null) {
         for (String arg : args) {
            String[] arr = arg.split(":");

            if (arr.length != 2 || !validArgs.containsKey(arr[0])) {
               DkLogs.info(this, "Invalid arguments: %s", arg);
               DkLogs.info(this, "Run this program with syntax as: java GenerateResource lang:vi strict:medium");
               DkLogs.info(this, "Please understand below available arguments before run the program:");
               DkLogs.info(this, "lang:xxx     Specify output folder for language, located at `resources/lang/xxx`");
               DkLogs.info(this, "             Where `xxx` is any language code, such as `vi`, `en`, `ja`..., default is `vi`.");
               DkLogs.info(this, "level:xxx    Specify generation level. Where `xxx` is one of {low, medium, high}, default is `medium`.");
               DkLogs.info(this, "             Case `low`: Collects Only target langs `(key.value)` -> Add them under `resources/lang/xxx/`");
               DkLogs.info(this, "             Case `medium`: Collects target langs `(key[._]value)` -> Add them under `resources/lang/xxx/`");
               DkLogs.info(this, "             Case `high`: Collects target langs (`key[._]value`)  -> Add them under `resources/lang/xxx/`");
               System.exit(-1);
            }

            validArgs.put(arr[0], arr[1]);
         }
      }

      lang = validArgs.getOrDefault("lang", "vi");
      levelLow = "low".equalsIgnoreCase(validArgs.get("level"));
      levelMedium = "medium".equalsIgnoreCase(validArgs.get("level"));
      levelHigh = "high".equalsIgnoreCase(validArgs.get("level"));
   }

   private void start() throws Exception {
      File routesDir = new File(rootPath + fs + "routes");
      File viewsDir = new File(rootPath + fs + "resources" + fs + "views");
      File httpDir = new File(rootPath + fs + "app" + fs + "Http");
      File viDir = new File(rootPath + fs + "resources" + fs + "lang" + fs + "vi");

      if (viDir.exists()) {
         if (!viDir.isDirectory()) {
            DkLogs.complain(this, "Require folder [%s]", viDir.getPath());
         }
      }
      else if (!viDir.mkdirs()) {
         DkLogs.complain(this, "Could not create folder [%s]", viDir.getPath());
      }

      for (File file : new File[] {routesDir, viewsDir, httpDir}) {
         if (!file.exists() || !file.isDirectory()) {
            DkLogs.complain(this, "Require folder [%s]", routesDir.getPath());
         }
      }

      // Generate Routes to app/Http/Constants
      DkLogs.info(this, "- Routes:");
      Map<String, String> routeEntries = collectWebRoutes(routesDir);
      generateIndexFile("Routes", routeEntries, routeEntries);

      // Generate Views to app/Http/Constants
      DkLogs.info(this, "- Views:");
      Map<String, String> viewEntries = collectViews(viewsDir);
      generateIndexFile("Views", viewEntries, viewEntries);

      // Generate Langs to app/Http/Constants
      DkLogs.info(this, "- Langs:");
      Map<String, Set<String>> file2key = new TreeMap<>();

      // collect-from/replace-in langs in resources/views/*
      collectAndReplaceLangEntriesInViews(viewsDir, file2key);

      // collect-from/replace-in langs in app/Http/*
      collectAndReplaceLangEntriesInHttp(httpDir, file2key);

      // create or update lang-entries to files in resources/lang/vi*
      Map<String, String>[] arr = createOrUpdateLangFiles(viDir, file2key);
      Map<String, String> langEntries = arr[0];
      Map<String, String> langComments = arr[1];

      generateIndexFile("Langs", langEntries, langComments);
   }

   /**
    * Collect-from/replace-in langs from resources/views/*
    *
    * @param viewsDir views directory
    * @param result_file2keys result map of filename -> set_of_lang_key, like: com -> welcome
    * @throws Exception e
    */
   private void collectAndReplaceLangEntriesInViews(File viewsDir, Map<String, Set<String>> result_file2keys) throws Exception {
      Map<String, Set<String>> file2keys = new TreeMap<>();

      // Collect from resources/views
      for (File viewFile : DkFiles.collectFilesRecursively(viewsDir)) {
         final String filename = DkFiles.calcFileNameWithoutExtension(viewFile);;

         if (filename == null || filename.length() == 0) {
            DkLogs.complain(this, "Invalid filename of file [%s]", getRelativePath(viewFile));
         }

         List<String> lines = DkFiles.readFileAsUtf8Lines(viewFile);

         int replacedItemCount = replaceItemsAndCollectKeys(
            getRelativePath(viewFile), lines,
            prefixForViews, suffixForViews,
            patternForViews, true,
            file2keys);

         if (replacedItemCount > 0) {
            mergeEntries(result_file2keys, file2keys);

            BufferedWriter writer = DkFiles.newUtf8Writer(viewFile);

            for (String line : lines) {
               writer.write(line);
               writer.write(ls);
            }

            writer.close();

            DkLogs.info(this, "   - Replaced %d entries in [%s]", replacedItemCount, getRelativePath(viewFile));
         }
      }
   }

   /**
    * Collect-from/Replace-in langs from app/Http/*
    * 
    * @param controllersDir dir
    * @param result_file2keys filename -> set_of_lang_key
    * @throws Exception e
    */
   private void collectAndReplaceLangEntriesInHttp(File controllersDir, Map<String, Set<String>> result_file2keys) throws Exception {
      Map<String, Set<String>> file2keys = new TreeMap<>();

      // Collect from resources/views
      for (File httpFile : DkFiles.collectFilesRecursively(controllersDir)) {
         List<String> lines = DkFiles.readFileAsUtf8Lines(httpFile);

         int replacedItemCount = replaceItemsAndCollectKeys(
            getRelativePath(httpFile), lines,
            prefixForCons, suffixForCons,
            patternForCons, false,
            file2keys);

         if (replacedItemCount > 0) {
            mergeEntries(result_file2keys, file2keys);
            tryImportLangsInController(lines);

            BufferedWriter writer = DkFiles.newUtf8Writer(httpFile);

            for (String line : lines) {
               writer.write(line);
               writer.write(ls);
            }

            writer.close();

            DkLogs.info(this, "   - Replaced %d entries in [%s]",
               replacedItemCount, getRelativePath(httpFile));
         }
      }
   }

   private void tryImportLangsInController(List<String> lines) {
      int importIndex = -1;
      int importPlacableIndex = 0;

      Pattern importPattern = Pattern.compile("use\\s+App\\\\Http\\\\Constants\\\\Langs\\s*;.*");

      for (int index = 0, N = lines.size(); index < N; ++index) {
         String line = lines.get(index).trim();

         if (importPattern.matcher(line).matches()) {
            return;
         }

         if (line.startsWith("<?php") || line.startsWith("namespace ")) {
            importPlacableIndex = index + 1;
         }
         else if (line.startsWith("use ")) {
            importPlacableIndex = index + 1;

            if (importIndex < 0) {
               importIndex = importPlacableIndex;
            }
         }
      }

      if (importIndex < 0) {
         importIndex = importPlacableIndex;
      }

      lines.add(importIndex, "use App\\Http\\Constants\\Langs;");
   }

   /**
    * Create or update lang-entries to files in resources/lang/vi*
    *
    * @param viDir vi directory
    * @param extras_file2keys filename -> set_of_lang_key, like: com -> msg_welcome
    *
    * @return array of 2 maps. First is map of field -> key, like: com_welcome -> com.welcome.
    *         Second is map of field -> value, like: com_welcome -> Xin chào.
    *
    * @throws Exception e
    */
   private Map<String, String>[] createOrUpdateLangFiles(File viDir, Map<String, Set<String>> extras_file2keys) throws Exception {
      Map<String, String> field2key = new TreeMap<>();
      Map<String, String> field2value = new TreeMap<>();

      // Calculate file2content map for all lang files
      Map<String, Map<String, String>> file2content = new TreeMap<>();

      for (File langFile : DkFiles.collectFilesRecursively(viDir)) {
         String fileName = DkFiles.calcFileNameWithoutExtension(langFile);
         Map<String, String> key2value = collectLangKeyValues(langFile);

         file2content.computeIfAbsent(fileName, k -> new TreeMap<>()).putAll(key2value);
      }

      // Merge result_file2keys map to file2content map.
      // After that, re-write lang file if needed.
      for (String filename : extras_file2keys.keySet()) {
         Map<String, String> filecontent = file2content.computeIfAbsent(filename, k -> new TreeMap<>());
         Map<String, String> newFilecontent = new TreeMap<>();

         for (String langKey : extras_file2keys.get(filename)) {
            if (! filecontent.containsKey(langKey)) {
               newFilecontent.put(langKey, filename + '.' + langKey);
            }
         }

         if (newFilecontent.size() > 0) {
            File updateFile = new File(viDir, filename + ".php");

            if (!updateFile.exists() || !updateFile.isFile()) {
               if (updateFile.createNewFile()) {
                  DkLogs.info(this, "   - Created new file [%s]", getRelativePath(updateFile));
               }
               else {
                  DkLogs.complain(this, "Could not create file [%s]", getRelativePath(updateFile));
               }
            }

            BufferedWriter writer = DkFiles.newUtf8Writer(updateFile);

            writer.write("<?php\n\n");
            writer.write("return [\n");

            for (String langkey : newFilecontent.keySet()) {
               writer.write(DkStrings.format("   \"%s\" => \"Please translate: %s\",\n", langkey, newFilecontent.get(langkey)));
            }
            writer.write(ls);
            for (String langkey : filecontent.keySet()) {
               writer.write(DkStrings.format("   \"%s\" => \"%s\",\n", langkey, filecontent.get(langkey)));
            }

            writer.write("];\n");
            writer.close();

            filecontent.putAll(newFilecontent);

            DkLogs.info(this, "   - Added new %d entries to [%s]",
               newFilecontent.size(), getRelativePath(updateFile));
         }
      }

      // Calculate result from file2content map
      for (String filename : file2content.keySet()) {
         for (String langkey : file2content.get(filename).keySet()) {
            String key = makeValidFieldKey(filename, langkey);
            String value = filename + '.' + langkey;

            field2key.put(key, value);
            field2value.put(key, file2content.get(filename).get(langkey));
         }
      }

      return new Map[] {field2key, field2value};
   }

   /**
    * Generate index file in app/Http/Constants from given entries.
    * @param type like Routes, Views, Langs...
    * @param entries map of key-value
    * @throws Exception e
    */
   private void generateIndexFile(String type, Map<String, String> entries, Map<String, String> comments) throws Exception {
      File outFile = new File(outDir + fs + type + ".php");

      if (!outFile.exists() && outFile.getParentFile().mkdirs() && !outFile.createNewFile()) {
         DkLogs.complain(this, "Could not create output [%s]", outFile.getPath());
      }

      BufferedWriter bw = DkFiles.newUtf8Writer(outFile);

      bw.write("<?php\n\n");
      bw.write("namespace App\\Http\\Constants;\n\n");
      bw.write("// This class is auto-generated by GenerateResouce. Don't modify it !\n");
      bw.write(DkStrings.format("class %s {\n", type));

      for (String key : entries.keySet()) {
         bw.write(DkStrings.format("   /** %s */\n", comments.get(key)));
         bw.write(DkStrings.format("   const %s = '%s';\n", key, entries.get(key)));
      }

      bw.write("}\n");
      bw.close();

      DkLogs.info(this, "   - Generated %d entries in [%s]", entries.size(), getRelativePath(outFile));
   }

   /**
    * Replace target items and collect lang keys from a file (lines).
    *
    * @param requestFullPath true if replacement is full path to lang key, otherwise Langs::key is used.
    */
   private int replaceItemsAndCollectKeys(String filePath, List<String> lines,
                                          String[] prefixes, String[] suffixes,
                                          Pattern[] patterns, boolean requestFullPath,
                                          Map<String, Set<String>> result_file2keys) {
      int replacedItemCount = 0;

      for (int lineIndex = 0, lineCount = lines.size(); lineIndex < lineCount; ++lineIndex) {
         String line = lines.get(lineIndex);

         for (int index = 0; index < patterns.length; ++index) {
            final String prefix = prefixes[index];
            final int prefixLength = prefix.length();
            final Pattern pattern = patterns[index];

            // Loop until this line does not match with this pattern
            int searchFromIndex = 0;

            while (searchFromIndex < line.length()) {
               int startIndex = line.indexOf(prefix, searchFromIndex);

               if (startIndex < 0) {
                  break;
               }

               searchFromIndex = startIndex + prefixLength;
               final String suffix = suffixes[index];
               int endIndex = line.indexOf(suffix, startIndex);

               if (endIndex > startIndex) {
                  // target is prefix(any_string_which_dost_not_contain_close_bracket)suffix
                  final String target = line.substring(startIndex, endIndex + suffix.length());

                  if (pattern.matcher(target).matches()) {
                     // Calculate key
                     // first, we touch to key from target by focus on string inside ()
                     startIndex += prefixLength;
                     String key = line.substring(startIndex, endIndex);
                     // second, check the touched key
                     if (keyPattern.matcher(key).matches()) {
                        key = DkStrings.trim(key, '"', '\'').trim();
                     }
                     else if (keyPattern_fullPath.matcher(key).matches() || keyPattern_langOnly.matcher(key).matches()) {
                        key = key.split("::")[1].trim();
                     }
                     else {
                        if (! validFullPathPattern.matcher(key).matches() && ! validNonFullPathPattern.matcher(key).matches()) {
                           DkLogs.println("   - [WARN] Inspected invalid target [%s] in [%s].",
                              target, filePath);
                        }
                        continue;
                     }

                     final int dotIndex = key.indexOf('.');

                     // we don't need make valid field key here since it was sastified before.
                     String fileKey = key.substring(0, dotIndex).trim();
                     String langKey = key.substring(dotIndex + 1).trim();

                     // collect key of file and lang
                     addToMap(result_file2keys, fileKey, langKey);

                     StringBuilder lineBuilder = new StringBuilder(line);
                     lineBuilder.replace(startIndex, endIndex, calcLangReferencePlacement(key, requestFullPath));
                     line = lineBuilder.toString();

                     ++replacedItemCount;
                  }
               }
            }
         }

         lines.set(lineIndex, line);
      }

      return replacedItemCount;
   }

   private void mergeEntries(Map<String, Set<String>> original, Map<String, Set<String>> extras) {
      for (String key : extras.keySet()) {
         original.computeIfAbsent(key, k -> new HashSet<>()).addAll(extras.get(key));
      }
   }

   /**
    * @param key com.welcome
    * @param requestFullPath true if caller need full path to /Langs, otherwise only key is returned.
    * @return App\Http\Constants\Langs::com_welcome
    */
   private String calcLangReferencePlacement(String key, boolean requestFullPath) {
      if (requestFullPath) {
         return DkStrings.format("\\App\\Http\\Constants\\Langs::%s", makeValidFieldKey(key));
      }
      return DkStrings.format("Langs::%s", makeValidFieldKey(key));
   }

   private void addToMap(Map<String, Set<String>> map, String key, String value) {
      map.computeIfAbsent(key, k -> new HashSet<>()).add(value);
   }

   /**
    * @param langFile file of lang
    * @return map of langkey -> its content, like: welcome -> Welcome to App
    * @throws Exception e
    */
   private Map<String, String> collectLangKeyValues(File langFile) throws Exception {
      Map<String, String> key2value = new TreeMap<>();
      List<String> lines = DkFiles.readFileAsUtf8Lines(langFile);

      for (String line : lines) {
         String[] arr = line.split("=>");

         if (arr.length == 2) {
            String langKey = arr[0].trim(); // "msg_test"
            String langValue = arr[1].trim(); // "this is test ' msg" ,

            // Only collect valid keys
            if (langKey.length() > 2 && langValue.length() > 2) {
               if (isDash(langKey.charAt(0)) && isDash(langKey.charAt(langKey.length() - 1)) && isDash(langValue.charAt(0))) {
                  String k = langKey.substring(1, langKey.length() - 1);

                  if (isValidFieldKey(k)) {
                     int dashIndex = Math.max(langValue.lastIndexOf('\''), langValue.lastIndexOf('"'));

                     if (dashIndex > 0) {
                        String v = langValue.substring(1, dashIndex);
                        key2value.put(k, v);
                     }
                  }
               }
            }
         }
      }

      return key2value;
   }

   private TreeMap<String, String> collectViews(File viewsDir) throws Exception {
      TreeMap<String, String> result = new TreeMap<>();
      File[] layoutDirs = viewsDir.listFiles();

      if (layoutDirs != null) {
         for (File layoutDir : layoutDirs) {
            if (layoutDir.isDirectory()) {
               String layoutDirName = DkFiles.calcFileNameWithoutExtension(layoutDir);
               File[] layoutFiles = layoutDir.listFiles();

               if (layoutFiles != null) {
                  for (File layoutFile : layoutFiles) {
                     if (layoutFile.isFile()) {
                        String layoutFileName = DkFiles.calcFileNameWithoutExtension(layoutFile);

                        String fieldName = makeValidFieldKey(layoutDirName, layoutFileName);
                        String fieldValue = layoutDirName + "." + layoutFileName;

                        result.put(fieldName, fieldValue);
                     }
                  }
               }
            }
         }
      }

      return result;
   }

   private TreeMap<String, String> collectWebRoutes(File routesDir) throws Exception {
      TreeMap<String, String> result = new TreeMap<>();
      File webRouteFile = new File(routesDir, "web.php");

      if (webRouteFile.exists()) {
         List<String> lines = DkFiles.readFileAsUtf8Lines(webRouteFile);

         for (int lineIndex = 0, lineCount = lines.size(); lineIndex < lineCount; ++lineIndex) {
            String line = lines.get(lineIndex).trim();

            // Only parse routes in a group
            if (line.startsWith("Route::group")) {
               final String prefix = parseGroupPrefix(line);

               if (prefix == null) {
                  continue;
               }

               ++lineIndex;

               for (; lineIndex < lineCount; ++lineIndex) {
                  line = lines.get(lineIndex).trim();

                  if (line.startsWith("});")) {
                     break;
                  }
                  if (line.startsWith("Route::")) {
                     final String suffix = parseSuffix(line);

                     if (suffix != null) {
                        String prefixOfKey = prefix.replaceAll("[{}]", "");
                        String suffixOfKey = suffix.replaceAll("[{}]", "");
                        
                        String key = makeValidFieldKey(prefixOfKey, suffixOfKey);
                        String value = DkStrings.trim(prefix + "/" + suffix, '/').trim();

                        result.put(key, value);
                     }
                  }
               }
            }
         }
      }

      return result;
   }

   // line = Route::group(['prefix' => '/xxx/yyy/', "middleware" => []], function() {
   private String parseGroupPrefix(String line) {
      final int N = line.length();
      int startIndex = line.indexOf("prefix");

      if (startIndex < 0) {
         return null;
      }

      int dashSymbolCount = 0;
      int endIndex = -1;

      for (int index = startIndex; index < N; ++index) {
         char ch = line.charAt(index);

         if (ch == '\'' || ch == '"') {
            if (++dashSymbolCount == 3) {
               endIndex = index;
               break;
            }
         }
      }

      if (startIndex < endIndex) {
         String target = line.substring(startIndex, endIndex);

         if (Pattern.compile("prefix[\"']\\s*=>\\s*[\"'][\\w/]+").matcher(target).matches()) {
            String[] arr = target.split("=>");
            String url = arr[1].trim().substring(1);

            return DkStrings.trim(url, '/');
         }
      }

      return null;
   }

   // line = Route::match(['get', 'post'], '/xxx/yyy', 'UserController@responseFile');
   // line = Route::post('/xxx/yyy', 'UserController@getUserDetail');
   // line = Route::get('/xxx/{id}/yyy', 'FileController@download');
   private String parseSuffix(String line) {
      String target = line.substring(line.indexOf("(") + 1, line.indexOf(")"));
      String[] arr = target.split(",");

      if (arr.length > 0) {
         for (String elem : arr) {
            if (elem.contains("/")) {
               String suffix = elem.trim();
               suffix = suffix.substring(2, Math.max(2, suffix.length() - 1)).trim();

               return DkStrings.trim(suffix, '/');
            }
         }
      }

      return null;
   }

   private static String makeValidFieldKey(String... keys) {
      StringBuilder sb = new StringBuilder();

      for (int i = 0, lastIndex = keys.length - 1; i <= lastIndex; ++i) {
         sb.append(DkStrings.trim(keys[i].replaceAll("\\W", "_"), '_'));
         
         if (i < lastIndex) {
            sb.append('_');
         }
      }

      return DkStrings.trim(sb.toString(), '_');
   }

   private static boolean isValidFieldKey(String key) {
      return key != null && fieldKeyPattern.matcher(key).matches();
   }

   private String getRelativePath(File f) {
      return f.getPath().substring(rootPath.length() + 1);
   }

   /**
    * @param c character
    * @return true if given character is ["] or [']. Otherwise false.
    */
   private boolean isDash(char c) {
      return c == '"' || c == '\'';
   }

   public static void main(String... args) {
      try {
         new GenerateResource(args).start();
      }
      catch (Exception e) {
         e.printStackTrace();
      }
   }
}
