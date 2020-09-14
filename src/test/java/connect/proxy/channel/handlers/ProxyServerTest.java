package connect.proxy.channel.handlers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.Options.ChunkedEncodingPolicy;
import com.github.tomakehurst.wiremock.junit.WireMockRule;

import connect.proxy.ProxyServer;

public class ProxyServerTest {
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig()
            .dynamicPort()
            .useChunkedTransferEncoding(ChunkedEncodingPolicy.NEVER));

    /**
     * Its purpose is to test the integration of handlers into the proxy server.
     * For detailed test of validations and exceptional cases, see tests for handlers.
     * 
     * @throws Exception
     */
    @Test
    public void validConnectionFlow_Tunnel() throws Exception {
        // Setup servers
        final int proxyPort = getRandomPort();
        ProxyServer proxy = new ProxyServer(proxyPort, new String[]{ "localhost" });
        proxy.start();

        stubFor(get(WireMock.anyUrl())
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/test")));

        int nThreads = 5;
        int nClients = 20;
        ExecutorService executor = Executors.newFixedThreadPool(nThreads);
        Future<?>[] futures = new Future[nClients];
        for (int i = 0; i < nClients; i++) {
        	futures[i] = executor.submit(() -> testConnection(proxyPort));
        }
        try {
        	for (int i = 0; i < nClients; i++) {
            	futures[i].get();
            }
        } finally {
        	executor.shutdownNow();
            proxy.close();
        }
    }

    private void testConnection(int proxyPort) {
    	// Client Setup
        TrustManager[] allCertTruster = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };

        try {
            SSLContext sslCtx = SSLContext.getInstance("SSL");
            sslCtx.init(null, allCertTruster, new SecureRandom());
            SSLSocketFactory sslSocketFactory = sslCtx.getSocketFactory();
            SSLSocket clientSocket = (SSLSocket) sslSocketFactory.createSocket("localhost", proxyPort);
            clientSocket.startHandshake();
            Scanner in = new Scanner(clientSocket.getInputStream());
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream());

            // Connect
            out.printf("CONNECT localhost:%d HTTP/1.1\r\n\r\n", wireMockRule.port()).flush();
            Assert.assertTrue(in.nextLine().startsWith("HTTP/1.1 200 OK"));
            in.nextLine();

            // Get
            String url = "/test" + (new Random()).nextInt();
            out.printf("GET %s HTTP/1.1\r\nHost: localhost\r\n\r\n", url).flush();
            Assert.assertTrue(in.nextLine().startsWith("HTTP/1.1 200 OK"));
            Assert.assertTrue(in.nextLine().startsWith("Content-Type: text/test"));

            verify(getRequestedFor(urlMatching(url))
                    .withHeader("Host", matching("localhost")));
        } catch (KeyManagementException e) {
        	throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
    }

    private static int getRandomPort() {
        while (true) {
            int port = (new Random()).nextInt(1 << 14) + 1024;
            try {
                (new Socket("localhost", port)).close();
            } catch(Exception e) {
                return port;
            }
        }
    }
}