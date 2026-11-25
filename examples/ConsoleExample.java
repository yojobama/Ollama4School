import com.ollama4school.OllamaOkHttpClient;
import com.ollama4school.OllamaPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * OllamaPool is plain Java, so you can test load balancing from a desktop JVM
 * (put okhttp, gson and the library classes on the classpath).
 *
 *   java ConsoleExample http://192.168.1.20:11434 http://192.168.1.21:11434
 */
public class ConsoleExample {
    public static void main(String[] args) throws Exception {
        OllamaPool pool = new OllamaPool(Arrays.asList(args));

        List<OllamaOkHttpClient.Message> messages = new ArrayList<>();
        messages.add(OllamaOkHttpClient.Message.system("Answer in one sentence."));
        messages.add(OllamaOkHttpClient.Message.user("What is gravity?"));

        pool.chatStream("lfm2.5:8b", messages, token -> System.out.print(token));
        System.out.println("\n\nAnswered by " + pool.lastServer());
        System.out.println("Stats: " + pool.stats());
    }
}
