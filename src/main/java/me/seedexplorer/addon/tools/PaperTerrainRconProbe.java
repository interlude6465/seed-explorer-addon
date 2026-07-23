package me.seedexplorer.addon.tools;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Small RCON driver used by local Paper terrain differential runs. */
public final class PaperTerrainRconProbe {
    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;

    private PaperTerrainRconProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException(
                "Usage: PaperTerrainRconProbe <host> <port> <password> <command>...");
        }
        try (Client client = new Client(args[0], Integer.parseInt(args[1]), args[2])) {
            client.login();
            for (int i = 3; i < args.length; i++) {
                try {
                    System.out.println("command=" + args[i] + " response=" + client.command(args[i]));
                } catch (EOFException exception) {
                    if (!args[i].equals("stop")) throw exception;
                    System.out.println("command=stop response=<server closed connection>");
                }
            }
        }
    }

    private static final class Client implements AutoCloseable {
        private final Socket socket = new Socket();
        private final DataInputStream input;
        private final DataOutputStream output;
        private int nextId = 1;

        private Client(String host, int port, String password) throws IOException {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setSoTimeout(300_000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            this.password = password;
        }

        private final String password;

        private void login() throws IOException {
            int id = nextId++;
            writePacket(id, TYPE_AUTH, password);
            for (int attempt = 0; attempt < 3; attempt++) {
                Packet packet = readPacket();
                if (packet.id() == -1) throw new IOException("RCON authentication failed");
                if (packet.id() == id) return;
            }
            throw new IOException("RCON authentication response was not received");
        }

        private String command(String command) throws IOException {
            int id = nextId++;
            writePacket(id, TYPE_COMMAND, command);
            StringBuilder result = new StringBuilder();
            while (true) {
                Packet packet = readPacket();
                if (packet.id() != id) continue;
                result.append(packet.payload());
                if (packet.payload().length() < 4096) return result.toString().trim();
            }
        }

        private void writePacket(int id, int type, String payload) throws IOException {
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            int length = 4 + 4 + body.length + 2;
            ByteBuffer packet = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
            packet.putInt(length).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
            output.write(packet.array());
            output.flush();
        }

        private Packet readPacket() throws IOException {
            int length;
            try {
                length = Integer.reverseBytes(input.readInt());
            } catch (EOFException exception) {
                throw new EOFException("RCON connection closed");
            }
            byte[] data = input.readNBytes(length);
            if (data.length != length) throw new EOFException("Incomplete RCON packet");
            ByteBuffer packet = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int id = packet.getInt();
            int type = packet.getInt();
            byte[] body = new byte[Math.max(0, length - 10)];
            packet.get(body);
            packet.get();
            packet.get();
            return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private record Packet(int id, int type, String payload) {
    }
}
