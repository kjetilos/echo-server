package no.kjetil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Server implementation of RFC-862 Echo Protocol
 */
public class EchoServer {

    private final Selector selector;
    private final Map<Object, EchoConnection> connections = new IdentityHashMap<Object, EchoConnection>();
    private int port;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Missing port argument");
            System.err.println("usage: java no.kjetil.EchoServer <port>");
            System.exit(1);
        }
        int port = Integer.parseInt(args[0]);
        EchoServer server = new EchoServer(port);
        server.start();
    }

    public EchoServer(int port) throws IOException {
        this.port = port;
        selector = Selector.open();
    }

    private void start() throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        ServerSocket socket = serverChannel.socket();
        socket.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                try {
                    if (key.isAcceptable()) {
                        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
                        onAccept(ch.accept());
                    } else if (key.isReadable()) {
                        SocketChannel ch = (SocketChannel) key.channel();
                        onRead(ch, key);
                    } else if (key.isWritable()) {
                        SocketChannel ch = (SocketChannel) key.channel();
                        onWrite(ch, key);
                    }
                } catch (IOException ex) {
                    /* Cleanup when things go wrong */
                    Object id = key.attachment();
                    connections.remove(id);
                    key.channel().close();
                    key.cancel();
                } finally {
                    /* Remove the key to signal that we have handled the io operation */
                    keys.remove();
                }
            }
        }
    }

    private void onWrite(SocketChannel channel, SelectionKey key) throws IOException {
        Object id = key.attachment();
        EchoConnection connection = connections.get(id);
        ByteBuffer buffer = connection.buffer;
        channel.write(buffer);

        if (buffer.remaining() == 0) {
            buffer.clear();
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void onRead(SocketChannel channel, SelectionKey key) throws IOException {
        Object id = key.attachment();
        EchoConnection connection = connections.get(id);
        ByteBuffer buffer = connection.buffer;
        int num = channel.read(buffer);
        if (num == -1) {
            onEof(channel, key);
        } else {
            buffer.flip();
            /* Now we want to write it back */
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void onEof(SocketChannel channel, SelectionKey key) throws IOException {
        /* Closing the socket will close the channel as well */
        channel.socket().close();
        key.cancel();
        connections.remove(key.attachment());
    }

    private void onAccept(SocketChannel channel) throws IOException {
        channel.configureBlocking(false);
        EchoConnection connection = new EchoConnection();
        connections.put(connection.id, connection);
        channel.register(selector, SelectionKey.OP_READ, connection.id);
    }
}

class EchoConnection {
    final ByteBuffer buffer = ByteBuffer.allocate(8192);
    final Object id;

    EchoConnection() {
        this.id = new Object();
    }
}
