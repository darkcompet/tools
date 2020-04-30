import java.util.LinkedHashMap;
import java.util.Map;

public class ArgParser {
   static Map<String, String> parseArgs(String[] args) {
      Map<String, String> map = new LinkedHashMap<>();

      if (args != null) {
         for (String arg : args) {
            String[] arr = arg.split(":");

            if (arr.length == 2) {
               map.put(arr[0], arr[1]);
            }
         }
      }

      return map;
   }
}
