package io.undertow.openssl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

/**
 * @author Stuart Douglas
 */
class EchoRunnable implements Runnable {
    private final ServerSocket serverSocket;
    private final SSLContext sslContext;
    private final AtomicReference<byte[]> sessionID;
    private final EngineCustomizer engineCustomizer;

    public EchoRunnable(ServerSocket serverSocket, SSLContext sslContext, AtomicReference<byte[]> sessionID) {
        this(serverSocket, sslContext, sessionID, null);
    }

    public EchoRunnable(ServerSocket serverSocket, SSLContext sslContext, AtomicReference<byte[]> sessionID, EngineCustomizer engineCustomizer) {
        this.serverSocket = serverSocket;
        this.sslContext = sslContext;
        this.sessionID = sessionID;
        this.engineCustomizer = engineCustomizer;
    }

    @Override
    public void run() {
        try {
            while (!serverSocket.isClosed()) {
                final Socket s = serverSocket.accept();
                Thread t = new Thread(() -> {
                    SSLEngine engine = sslContext.createSSLEngine();
                    if(engineCustomizer != null) {
                        engine = engineCustomizer.modify(engine);
                    }
                    engine.setUseClientMode(false);
                    byte[] bytes = new byte[20000];
                    ByteBuffer in = ByteBuffer.allocateDirect(20000);
                    ByteBuffer out = ByteBuffer.allocateDirect(20000);
                    ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
                    try {
                        SSLEngineResult result = null;
                        boolean forceWrap = false;
                        boolean moreData = false;
                        while (result == null || result.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.FINISHED) {
                            if(!moreData) {
                                in.clear();
                            }
                            out.clear();
                            if (result == null || result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                                if(!moreData) {
                                    int read = s.getInputStream().read(bytes);
                                    in.put(bytes, 0, read);
                                    in.flip();
                                }
                                result = engine.unwrap(in, out);
                                moreData = in.hasRemaining();
                                if (result.bytesProduced() > 0) {
                                    out.flip();
                                    byte[] b = new byte[out.remaining()];
                                    out.get(b);
                                    dataStream.write(b);
                                }
                            } else if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP || forceWrap) {
                                forceWrap = false;
                                in.flip();
                                result = engine.wrap(in, out);
                                out.flip();
                                int len = out.remaining();
                                out.get(bytes, 0, len);
                                s.getOutputStream().write(bytes, 0, len);
                            } else if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                                Runnable task = engine.getDelegatedTask();
                                while (task != null) {
                                    task.run();
                                    task = engine.getDelegatedTask();
                                }
                                if(moreData) {
                                    result = null;
                                } else {
                                    forceWrap = true;
                                }
                            } else {
                                throw new RuntimeException(result.toString());
                            }
                        }
                        sessionID.set(engine.getSession().getId());
                        while (true) {
                            in.clear();
                            out.clear();
                            boolean close = false;
                            if (dataStream.size() > 0) {
                                byte[] dataBytes = dataStream.toByteArray();
                                int i;
                                for ( i = 0; i < dataBytes.length; i++) {
                                    byte b = dataBytes[i];
                                    if (b == 0) {
                                        close = true;
                                        break;
                                    }
                                }
                                String read = new String(dataBytes, 0, i);
                                System.out.println(read);
                                dataStream.reset();
                                in.put((read).getBytes(StandardCharsets.US_ASCII));
                                in.flip();
                                result = engine.wrap(in, out);
                                out.flip();
                                int len = out.remaining();
                                out.get(bytes, 0, len);
                                s.getOutputStream().write(bytes, 0, len);
                                in.clear();
                                out.clear();
                                if(close) {
                                    engine.closeOutbound();
                                    s.close();
                                }
                            }
                            int read = s.getInputStream().read(bytes);
                            if (read == -1) {
                                return;
                            }
                            System.out.println(read);
                            in.put(bytes, 0, read);
                            in.flip();
                            result = engine.unwrap(in, out);
                            boolean prod = false;
                            if(result.bytesProduced() > 0) {
                                prod = true;
                                result = engine.unwrap(in, out);
                            }
                            if (result.bytesProduced() > 0 || prod) {
                                out.flip();
                                byte[] b = new byte[out.remaining()];
                                out.get(b);
                                dataStream.write(b);
                            }
                        }
                    } catch (Exception e) {
                        try {
                            s.close();
                        } catch (IOException e1) {
                            //ignore
                        }
                        //e.printStackTrace();
                        //throw new RuntimeException(e);
                    }

                });
                t.start();
            }
        } catch (Exception e) {
            //e.printStackTrace();
            //throw new RuntimeException(e);
        }
    }

    public interface EngineCustomizer {

        SSLEngine modify(SSLEngine engine);
    }

    public void stop() throws IOException {
        serverSocket.close();
    }
}