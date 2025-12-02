import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 To compile and run:

 javac src/*.java && java -cp src ChatterboxServer PORT_NUMBER CREDENTIALS_FILE

 Example:
 javac src/*.java && java -cp src ChatterboxServer 12345 sample_users.txt
*/

/**
 * A simple multi-client chat server.
 *
 * Behavior:
 * - Accepts TCP connections on the given port.
 * - Prompts each client for "username password".
 * - Authenticates against the credentials map.
 * - After auth, broadcasts each client message to all connected clients.
 */
public class ChatterboxServer {
    /** Maximum simultaneous authenticated clients / pool size. */
    private static final int MAX_CONNECTIONS = 100;

    private final int port;

    /**
     * Map of username -> active connection.
     * Concurrent because multiple client threads access it.
     */
    private final Map<String, Connection> connections;

    /** Map of username -> password loaded at startup. */
    private final Map<String, String> user2pass;

    /**
     * Simple wrapper around a Socket that provides line-based send/receive.
     * Closing the Connection closes the underlying socket and streams.
     */
    private class Connection implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter bw;
        private final BufferedReader br;

        /**
         * Create a Connection for the given socket, using UTF-8 readers/writers.
         *
         * @param socket the socket for a newly accepted client
         * @throws IOException if the socket streams cannot be opened
         */
        public Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.br = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.bw = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        /**
         * Send a line of text to the client.
         *
         * @param msg message line to send (without newline)
         * @throws IOException if the client connection is broken
         */
        public void sendln(String msg) throws IOException {
            bw.write(msg);
            bw.newLine();
            bw.flush();
        }

        /**
         * Read a line of text from the client.
         *
         * @return the next line, or null if the client closed the connection
         * @throws IOException if a network error occurs while reading
         */
        public String readLine() throws IOException {
            return br.readLine();
        }

        /**
         * Close the connection and underlying socket.
         *
         * @throws IOException if the socket cannot be closed cleanly
         */
        @Override
        public void close() throws IOException {
            try { br.close(); } catch (IOException ignored) {}
            try { bw.close(); } catch (IOException ignored) {}
            socket.close();
        }
    }

    /**
     * Entry point.
     *
     * Required args:
     * args[0] = port number (1..65535)
     * args[1] = credentials file path
     *
     * @param args command-line arguments
     * @throws IOException only if something unexpected slips past validation
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java -cp src ChatterboxServer <port> <credentialsFile>");
            System.err.println("Example: java -cp src ChatterboxServer 12345 sample_users.txt");
            System.exit(1);
        }

        int port;
        try {
            port = Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) {
                System.err.println("Error: port must be between 1 and 65535, got " + port);
                System.exit(1);
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: port must be an integer, got '" + args[0] + "'");
            System.exit(1);
            return; // unreachable, keeps compiler happy
        }

        String filename = args[1];
        Map<String, String> creds;
        try {
            creds = loadCredentials(filename);
        } catch (IOException e) {
            System.err.println("Error: could not load credentials from '" + filename + "'");
            System.err.println("Reason: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Loaded " + creds.size() + " credential(s). Starting server on port " + port + "...");
        ChatterboxServer server = new ChatterboxServer(port, creds);
        server.serve();
    }

    /**
     * Load username/password pairs from a file.
     *
     * File format:
     * - whitespace-separated tokens
     * - interpreted as (user, pass) pairs
     *
     * @param filename path to the credentials file
     * @return a map of username -> password
     * @throws IOException if the file cannot be read or is malformed
     */
    private static Map<String, String> loadCredentials(String filename) throws IOException {
        Map<String, String> creds = new HashMap<>();
        try (Scanner sc = new Scanner(new File(filename), StandardCharsets.UTF_8)) {
            while (sc.hasNext()) {
                String user = sc.next();
                if (!sc.hasNext()) {
                    throw new IOException(
                            "Credentials file has an odd number of tokens; missing password for user '" + user + "'");
                }
                String pass = sc.next();
                creds.put(user, pass);
            }
        }
        return creds;
    }

    /**
     * Create a new ChatterboxServer.
     *
     * @param port port to listen on
     * @param user2pass map of username -> password
     */
    public ChatterboxServer(int port, Map<String, String> user2pass) {
        this.port = port;
        this.connections = new ConcurrentHashMap<>();
        this.user2pass = user2pass;
    }

    /**
     * Accept clients forever and handle each one in the thread pool.
     *
     * @throws IOException if the ServerSocket cannot be opened
     */
    public void serve() throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(MAX_CONNECTIONS);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port + "...");
            while (true) {
                Socket socket = serverSocket.accept();
                pool.submit(() -> {
                    try {
                        connectClient(socket);
                    } catch (IOException e) {
                        System.err.println("Client handler failed: " + e.getMessage());
                    }
                });
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Broadcast a message to all currently connected clients.
     *
     * @param user sender username
     * @param message message text
     */
    public void sendToAll(String user, String message) {
        String formatted = "[" + user + "]: " + message;
        System.out.println(formatted);

        for (Connection connection : connections.values()) {
            try {
                connection.sendln(formatted);
            } catch (IOException e) {
                // If a client can't be written to, they likely disconnected.
                System.err.println("Warning: failed to send message to a client (they may have disconnected).");
            }
        }
    }

    /**
     * Handle a single client: authenticate, then relay/broadcast their messages.
     *
     * Authentication:
     * - Server prompts for a single line: "username password"
     * - Any failure results in an explanatory message and disconnect.
     *
     * @param socket newly accepted socket
     * @throws IOException if connection setup fails
     */
    public void connectClient(Socket socket) throws IOException {
        Connection connection = new Connection(socket);

        try (connection) {
            connection.sendln("Please enter your username and password, separated by a space:");

            String authString = connection.readLine();
            if (authString == null) {
                // Client disconnected before sending auth line.
                return;
            }

            String[] parts = authString.trim().split("\\s+");
            if (parts.length != 2) {
                connection.sendln("Authentication failed: expected 'username password'.");
                connection.sendln("Closing connection. Please try again.");
                return;
            }

            String user = parts[0];
            String pass = parts[1];

            String expectedPass = user2pass.get(user);
            if (expectedPass == null || !expectedPass.equals(pass)) {
                connection.sendln("Authentication failed: invalid username or password.");
                connection.sendln("Closing connection. Please try again.");
                return;
            }

            if (connections.putIfAbsent(user, connection) != null) {
                connection.sendln("Authentication failed: user '" + user + "' is already connected.");
                connection.sendln("Disconnect your other client and try again.");
                return;
            }

            try {
                connection.sendln("Welcome to the server, " + user + "!");
                connection.sendln("Be kind and respectful to your classmates.");

                String line;
                while ((line = connection.readLine()) != null) {
                    sendToAll(user, line);
                }
            } finally {
                connections.remove(user);
                System.out.println("User '" + user + "' disconnected.");
            }

        } catch (IOException e) {
            System.err.println("Connection error for client: " + e.getMessage());
        }
    }
}
