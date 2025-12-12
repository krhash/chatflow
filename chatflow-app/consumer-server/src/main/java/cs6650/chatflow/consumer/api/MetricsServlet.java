package cs6650.chatflow.consumer.api;

import cs6650.chatflow.consumer.commons.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Metrics API endpoint that serves pre-computed, cached data.
 * This endpoint is non-blocking and extremely fast.
 */
@WebServlet(Constants.METRICS_ENDPOINT)
public class MetricsServlet extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(MetricsServlet.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        logger.debug("Metrics API called from: {}", req.getRemoteAddr());

        try {
            // Get metrics service from servlet context
            ServletContext context = getServletContext();
            MetricsService metricsService = (MetricsService) context.getAttribute("metricsService");

            if (metricsService == null) {
                logger.error("Metrics service not found in servlet context");
                sendError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Metrics service not available");
                return;
            }

            // Get the latest cached metrics (non-blocking)
            Map<String, Object> metrics = metricsService.getMetrics();

            // Send JSON response
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(gson.toJson(metrics));

        } catch (Exception e) {
            logger.error("Error serving metrics from cache", e);
            sendError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to serve metrics: " + e.getMessage());
        }
    }

    /**
     * Helper: Send error response
     */
    private void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);

        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("timestamp", new Date().toString());

        resp.getWriter().write(gson.toJson(error));
    }
}
