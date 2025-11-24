package cs6650.chatflow.consumer.api;

import cs6650.chatflow.consumer.database.DatabaseService;
import cs6650.chatflow.consumer.messaging.MessageConsumerManager;
import cs6650.chatflow.consumer.commons.Constants;
import com.google.gson.Gson;

import javax.servlet.ServletContext;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint for consumer server
 */
@WebServlet(Constants.HEALTH_ENDPOINT)
public class HealthServlet extends HttpServlet {
    private static final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> health = new HashMap<>();

        try {
            // Check database service
            ServletContext context = getServletContext();
            DatabaseService databaseService = (DatabaseService) context.getAttribute("databaseService");

            boolean dbHealthy = databaseService != null && databaseService.isHealthy();
            health.put("database", dbHealthy ? "UP" : "DOWN");
            health.put("databaseType", databaseService != null ? databaseService.getDatabaseType() : "UNKNOWN");

            // Check consumers
            MessageConsumerManager consumerManager = MessageConsumerManager.getInstance();
            health.put("consumers", consumerManager.isStarted() ? "UP" : "DOWN");
            health.put("consumerCount", consumerManager.getConsumerCount());

            // Overall status
            boolean healthy = dbHealthy && consumerManager.isStarted();
            health.put("status", healthy ? "UP" : "DOWN");

            resp.setContentType("application/json");
            resp.setStatus(healthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.getWriter().write(gson.toJson(health));

        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());

            resp.setContentType("application/json");
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.getWriter().write(gson.toJson(health));
        }
    }
}
