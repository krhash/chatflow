package cs6650.chatflow.server.handler.rest;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;

import static cs6650.chatflow.server.commons.Constants.HEALTH_ENDPOINT;

/**
 * Health check servlet responding with a simple JSON status message.
 */
@WebServlet(HEALTH_ENDPOINT)
public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write("{\"status\": \"UP\"}");
    }
}
