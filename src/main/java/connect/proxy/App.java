package connect.proxy;

import java.util.Arrays;

/**
 * The Entry Point of the App
 * 
 * @author carlos
 *
 */
public class App {
    // Configuration
    // NEXT: Store as external configuration
    private static int DEFAULT_PORT = 8443;
    private static String[] DEFAULT_HOST_WHITELIST = new String[] { "localhost" };

    // Main
    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String[] hostWhitelist = null;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        if (args.length > 1) {
        	hostWhitelist = Arrays.copyOfRange(args, 1, args.length);
        } else {
        	hostWhitelist = DEFAULT_HOST_WHITELIST;
        }

        ProxyServer proxyServer = new ProxyServer(port, hostWhitelist);
        proxyServer.start();
        proxyServer.waitForClose();
    }
}