import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class PaymentServiceMock {
	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(9001), 0);
		server.createContext("/status", PaymentServiceMock::handleStatus);
		server.createContext("/health", PaymentServiceMock::handleHealth);
		server.start();
		System.out.println("payment-service mock running on http://localhost:9001");
	}

	private static void handleStatus(HttpExchange exchange) throws IOException {
		String response = "{\"service\":\"payment-service\",\"version\":\"v1\",\"status\":\"success\"}";
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