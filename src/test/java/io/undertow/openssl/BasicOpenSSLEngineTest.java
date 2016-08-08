package io.undertow.openssl;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Stuart Douglas
 */
public class BasicOpenSSLEngineTest {

    public static final String MESSAGE = "Hello World";

    @BeforeClass
    public static void setup() {
        OpenSSLProvider.register();
        System.setProperty("javax.net.ssl.keyStore", new File("src/test/resources/client.keystore").getAbsolutePath());
        System.setProperty("javax.net.ssl.trustStore", new File("src/test/resources/client.truststore").getAbsolutePath());
        System.setProperty("javax.net.ssl.keyStorePassword", "password");
    }

    @Test
    public void basicOpenSSLTest() throws IOException, NoSuchAlgorithmException {
        try (ServerSocket serverSocket = new ServerSocket(7676)) {
            final AtomicReference<byte[]> sessionID = new AtomicReference<>();
            final SSLContext sslContext = SSLTestUtils.createSSLContext("openssl.TLSv1");

            Thread acceptThread = new Thread(new EchoRunnable(serverSocket, sslContext, sessionID));
            acceptThread.start();
            final SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            socket.connect(new InetSocketAddress("localhost", 7676));
            socket.getOutputStream().write(MESSAGE.getBytes(StandardCharsets.US_ASCII));
            byte[] data = new byte[100];
            int read = socket.getInputStream().read(data);

            Assert.assertEquals(MESSAGE, new String(data, 0, read));
            Assert.assertArrayEquals(socket.getSession().getId(), sessionID.get());
        }
    }


    @Test
    public void openSslLotsOfDataTest() throws IOException, NoSuchAlgorithmException {
        try (ServerSocket serverSocket = new ServerSocket(7676)) {
            final AtomicReference<byte[]> sessionID = new AtomicReference<>();
            final SSLContext sslContext = SSLTestUtils.createSSLContext("openssl.TLSv1");

            EchoRunnable target = new EchoRunnable(serverSocket, sslContext, sessionID);
            Thread acceptThread = new Thread(target);
            acceptThread.start();
            final SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            socket.connect(new InetSocketAddress("localhost", 7676));
            String message = generateMessage(1000);
            socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(new byte[]{0});

            Assert.assertEquals(message, new String(SSLTestUtils.readData(socket.getInputStream())));
            Assert.assertArrayEquals(socket.getSession().getId(), sessionID.get());
        }
    }

    private static String generateMessage(int repetitions) {
        final StringBuilder builder = new StringBuilder(repetitions * MESSAGE.length());
        for (int i = 0; i < repetitions; ++i) {
            builder.append(MESSAGE);
        }
        return builder.toString();
    }
}