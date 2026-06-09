import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slugyzeon.plugin.youtube.clients.*;
import com.slugyzeon.plugin.youtube.YouTubeProxyHandler;

public class test {
    public static void main(String[] args) throws Exception {
        YouTubeProxyHandler handler = new YouTubeProxyHandler();
        YouTubeProxyHandler.VideoInfo info = handler.getVideoInfo("88V48l19vA8");
        System.out.println(info != null ? "Found title: " + info.title : "Failed to find info!");
    }
}
