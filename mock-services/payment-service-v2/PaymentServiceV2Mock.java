import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Mock payment-service v2 — listens on port 9002.
 *
 * Run with:
 *   cd mock-services/payment-service-v2
 *   javac PaymentServiceV2Mock.java
 *   java PaymentServiceV2Mock
 */
public class PaymentServiceV2Mock {
	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(9002), 0);
		server.createContext("/status", PaymentServiceV2Mock::handleStatus);
		server.createContext("/health", PaymentServiceV2Mock::handleHealth);
		server.start();
		System.out.println("payment-service-v2 mock running on http://localhost:9002");
	}

	private static void handleStatus(HttpExchange exchange) throws IOException {
		String response = "{\"service\":\"payment-service\",\"version\":\"v2\",\"status\":\"success\",\"feature\":\"new-checkout-flow\"}";
		writeJson(exchange, 200, response);
	}

	private static void handleHealth(HttpExchange exchange) throws IOException {
		String response = "{\"status\":\"UP\"}";
		writeJson(exchange, 200, response);
	}

	private static void writeJson(HttpExchange exchange, int statusCode, String response) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(statusCode, responseBytes.length);
		try (OutputStream outputStream = exchange.getResponseBody()) {
			outputStream.write(responseBytes);
		}
	}
}
